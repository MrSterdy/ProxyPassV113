package com.nukkitx.proxypass.network.bedrock.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.google.common.base.Preconditions;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.factories.DefaultJWSVerifierFactory;
import com.nimbusds.jose.shaded.json.JSONObject;
import com.nimbusds.jwt.SignedJWT;
import com.nukkitx.protocol.bedrock.BedrockClient;
import com.nukkitx.protocol.bedrock.BedrockServerSession;
import com.nukkitx.protocol.bedrock.handler.BedrockPacketHandler;
import com.nukkitx.protocol.bedrock.packet.LoginPacket;
import com.nukkitx.protocol.bedrock.util.EncryptionUtils;
import com.nukkitx.proxypass.ProxyPass;
import com.nukkitx.proxypass.network.bedrock.util.ForgeryUtils;
import io.netty.util.AsciiString;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.security.interfaces.ECPublicKey;
import java.util.UUID;

@Log4j2
@RequiredArgsConstructor
public class UpstreamPacketHandler implements BedrockPacketHandler {

    private final BedrockServerSession session;
    private final ProxyPass proxy;
    private JSONObject clientData;
    private JSONObject extraData;
    private ArrayNode chainData;
    private AuthData authData;

    private static boolean validateChainData(JsonNode data) throws Exception {
        ECPublicKey lastKey = null;
        boolean validChain = false;
        for (JsonNode node : data) {
            JWSObject jwt = JWSObject.parse(node.asText());

            if (!validChain) {
                validChain = verifyJwt(jwt, EncryptionUtils.getMojangPublicKey());
            }

            if (lastKey != null) {
                verifyJwt(jwt, lastKey);
            }

            JsonNode payloadNode = ProxyPass.JSON_MAPPER.readTree(jwt.getPayload().toString());
            JsonNode ipkNode = payloadNode.get("identityPublicKey");
            Preconditions.checkState(ipkNode != null && ipkNode.getNodeType() == JsonNodeType.STRING, "identityPublicKey node is missing in chain");
            lastKey = EncryptionUtils.generateKey(ipkNode.asText());
        }
        return validChain;
    }

    private static boolean verifyJwt(JWSObject jwt, ECPublicKey key) throws JOSEException {
        return jwt.verify(new DefaultJWSVerifierFactory().createJWSVerifier(jwt.getHeader(), key));
    }

    @Override
    public boolean handle(LoginPacket packet) {
        JsonNode certData;
        try {
            certData = ProxyPass.JSON_MAPPER.readTree(packet.getChainData().toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("Certificate JSON can not be read.");
        }

        JsonNode certChainData = certData.get("chain");
        if (certChainData.getNodeType() != JsonNodeType.ARRAY) {
            throw new RuntimeException("Certificate data is not valid");
        }
        chainData = (ArrayNode) certChainData;

        boolean validChain;
        try {
            validChain = validateChainData(certChainData);

            log.debug("Is player data valid? {}", validChain);
            JWSObject jwt = JWSObject.parse(certChainData.get(certChainData.size() - 1).asText());
            JsonNode payload = ProxyPass.JSON_MAPPER.readTree(jwt.getPayload().toBytes());

            if (payload.get("extraData").getNodeType() != JsonNodeType.OBJECT) {
                throw new RuntimeException("AuthData was not found!");
            }

            extraData = (JSONObject) jwt.getPayload().toJSONObject().get("extraData");

            this.authData = new AuthData(extraData.getAsString("displayName"),
                    UUID.fromString(extraData.getAsString("identity")));

            if (payload.get("identityPublicKey").getNodeType() != JsonNodeType.STRING) {
                throw new RuntimeException("Identity Public Key was not found!");
            }
            ECPublicKey identityPublicKey = EncryptionUtils.generateKey(payload.get("identityPublicKey").textValue());

            JWSObject clientJwt = JWSObject.parse(packet.getClientData().toString());
            verifyJwt(clientJwt, identityPublicKey);

            clientData = new JSONObject(clientJwt.getPayload().toJSONObject());
            initializeProxySession();
        } catch (Exception e) {
            session.disconnect("disconnectionScreen.internalError.cantConnect");
            throw new RuntimeException("Unable to complete login", e);
        }
        return true;
    }

    private void initializeProxySession() {
        log.debug("Initializing proxy session");
        BedrockClient client = proxy.newClient();
        client.setRakNetVersion(ProxyPass.CODEC.getRaknetProtocolVersion());
        client.connect(proxy.getTargetAddress()).whenComplete((downstream, throwable) -> {
            if (throwable != null) {
                log.error("Unable to connect to downstream server " + proxy.getTargetAddress(), throwable);
                return;
            }
            downstream.setPacketCodec(ProxyPass.CODEC);
            ProxyPlayerSession proxySession = new ProxyPlayerSession(this.session, downstream, this.proxy, this.authData);
            try {
                JWSObject jwt = JWSObject.parse(chainData.get(chainData.size() - 1).asText());
                JsonNode payload = ProxyPass.JSON_MAPPER.readTree(jwt.getPayload().toBytes());
                proxySession.getLogger().saveJson("chainData", payload);
                proxySession.getLogger().saveJson("clientData", this.clientData);
            } catch (Exception e) {
                log.error("JSON output error: " + e.getMessage(), e);
            }
            SignedJWT authData = ForgeryUtils.forgeAuthData(proxySession.getProxyKeyPair(), extraData);
            JWSObject clientData = ForgeryUtils.forgeSkinData(proxySession.getProxyKeyPair(), this.clientData);
            chainData.remove(chainData.size() - 1);
            chainData.add(authData.serialize());
            JsonNode json = ProxyPass.JSON_MAPPER.createObjectNode().set("chain", chainData);
            AsciiString chainData;
            try {
                chainData = new AsciiString(ProxyPass.JSON_MAPPER.writeValueAsBytes(json));
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }

            LoginPacket login = new LoginPacket();
            login.setChainData(chainData);
            login.setClientData(AsciiString.of(clientData.serialize()));
            login.setProtocolVersion(ProxyPass.PROTOCOL_VERSION);
            login.setVersion(LoginPacket.Version.VANILLA);

            downstream.sendPacketImmediately(login);

            this.session.setBatchHandler(proxySession.getUpstreamBatchHandler());
            downstream.setBatchHandler(proxySession.getDownstreamTailHandler());
            downstream.setLogging(true);
            downstream.setPacketHandler(new DownstreamInitialPacketHandler(downstream, proxySession, this.proxy));
            downstream.addDisconnectHandler(disconnectReason -> this.session.disconnect());

            //SkinUtils.saveSkin(proxySession, this.skinData);
        });
    }
}

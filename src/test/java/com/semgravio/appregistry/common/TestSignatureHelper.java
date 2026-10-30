package com.semgravio.appregistry.common;

import com.semgravio.appregistry.domain.signature.port.ApiSecretEncrypter;
import com.cartisan.data.jpa.id.TsidGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 测试用 HMAC-SHA256 签名工具，与 cartisan-openapi {@code SignatureVerificationFilter}
 * 的签名算法完全一致。
 *
 * <p>使用方式：
 * <pre>{@code
 *   TestSignatureHelper signer = TestSignatureHelper.setupCaller(jdbcTemplate, encrypter);
 *   mvc.perform(signer.sign(get("/api/app-registry/apps/1"), null));
 *   mvc.perform(signer.sign(post("/api/app-registry/apps").content(json), json));
 * }</pre>
 *
 * @since 0.1.0
 */
public class TestSignatureHelper {

    private static final String TEST_CALLER_APP_CODE = "__test_caller";
    private static final String TEST_CALLER_SECRET = "test-caller-secret-32bytes!";

    private final String apiKey;
    private final String apiSecret;

    /**
     * 在测试 DB 中插入一个有签名凭证的 caller 应用，返回签名工具。
     *
     * <p>直接插库（不走 API），避免鸡生蛋问题：管理接口需验签，但验签需要已有 caller 的凭证。
     */
    public static TestSignatureHelper setupCaller(JdbcTemplate jdbcTemplate, ApiSecretEncrypter encrypter) {
        long callerAppId = TsidGenerator.newInstance().generate();
        long callerKeyId = TsidGenerator.newInstance().generate();
        String encryptedSecret = encrypter.encrypt(TEST_CALLER_SECRET);
        jdbcTemplate.update(
                "INSERT INTO ar_registered_apps (id, app_code, name, status, created_at, updated_at, deleted) " +
                        "VALUES (?, ?, ?, ?, now(), now(), false) " +
                        "ON CONFLICT (app_code) DO NOTHING",
                callerAppId, TEST_CALLER_APP_CODE, "Test Caller", 1);
        jdbcTemplate.update(
                "INSERT INTO ar_api_keys (id, app_id, api_key, api_secret, status, created_at, updated_at, deleted) " +
                        "VALUES (?, ?, ?, ?, ?, now(), now(), false) " +
                        "ON CONFLICT (api_key) DO NOTHING",
                callerKeyId, callerAppId, TEST_CALLER_APP_CODE, encryptedSecret, 1);
        return new TestSignatureHelper(TEST_CALLER_APP_CODE, TEST_CALLER_SECRET);
    }

    public TestSignatureHelper(String apiKey, String apiSecret) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
    }

    /**
     * 对请求添加签名头。
     *
     * @param builder     MockMvc 请求构建器
     * @param bodyContent 请求体内容（GET 请求传 {@code null} 或空串）
     * @return 已添加签名头的 builder
     */
    public MockHttpServletRequestBuilder sign(MockHttpServletRequestBuilder builder, String bodyContent) {
        byte[] bodyBytes = bodyContent != null ? bodyContent.getBytes(StandardCharsets.UTF_8) : new byte[0];
        String bodyDigest = sha256Hex(bodyBytes);

        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = UUID.randomUUID().toString();

        String stringToSign = buildStringToSign(apiKey, bodyDigest, nonce, timestamp, Map.of());
        String sign = hmacSha256Hex(stringToSign, apiSecret);

        return builder
                .header("X-Api-Key", apiKey)
                .header("X-Timestamp", timestamp)
                .header("X-Nonce", nonce)
                .header("X-Body-Digest", bodyDigest)
                .header("X-Sign", sign);
    }

    // ---- package-private for testing ----

    static String buildStringToSign(String apiKey, String bodyDigest, String nonce,
                                     String timestamp, Map<String, String> queryParams) {
        TreeMap<String, String> map = new TreeMap<>();
        map.put("apiKey", apiKey);
        map.put("bodyDigest", bodyDigest);
        map.put("nonce", nonce);
        map.put("timestamp", timestamp);
        map.putAll(queryParams);

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append("&");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }

    static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    static String hmacSha256Hex(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] result = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(result);
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate HMAC-SHA256", e);
        }
    }
}

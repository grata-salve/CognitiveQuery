package com.example.cognitivequery.service.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

@Service
@Slf4j
public class EncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTE = 12; // Standard IV length for GCM
    private static final int TAG_LENGTH_BIT = 128; // Standard tag length for GCM

    private final SecretKey secretKey; // Holds the AES key

    // Constructor injects the Base64 encoded key from application properties/environment
    public EncryptionService(@Value("${app.db.encryption.key}") String base64Key) {
        if (!StringUtils.hasText(base64Key)) {
            log.error("!!! CRITICAL: Database encryption key ('app.db.encryption.key') is not configured. Application startup failed. !!!");
            throw new IllegalStateException("DB Encryption key 'app.db.encryption.key' is not configured.");
        }
        try {
            byte[] decodedKey = Base64.getDecoder().decode(base64Key);
            // Validate key length for AES
            if (decodedKey.length != 16 && decodedKey.length != 24 && decodedKey.length != 32) {
                throw new IllegalArgumentException("Invalid AES key length: " + decodedKey.length * 8 + " bits. Must be 128, 192, or 256 bits.");
            }
            this.secretKey = new SecretKeySpec(decodedKey, 0, decodedKey.length, "AES");
            log.info("EncryptionService initialized successfully.");
        } catch (IllegalArgumentException e) {
            log.error("Error initializing EncryptionService: Invalid Base64 key provided.", e);
            throw new IllegalStateException("Invalid encryption key provided: " + e.getMessage(), e);
        }
    }

    /**
     * Encrypts data using AES/GCM. Prepends a random IV to the ciphertext.
     *
     * @param plainText The text to encrypt.
     * @return Optional<String> containing Base64 encoded (IV + Ciphertext), or empty on error.
     */
    public Optional<String> encrypt(String plainText) {
        if (plainText == null) return Optional.empty();
        try {
            byte[] iv = new byte[IV_LENGTH_BYTE];
            SecureRandom random = SecureRandom.getInstanceStrong(); // Use strong random
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(TAG_LENGTH_BIT, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // Combine IV + Ciphertext
            byte[] encryptedDataWithIv = new byte[IV_LENGTH_BYTE + cipherText.length];
            System.arraycopy(iv, 0, encryptedDataWithIv, 0, IV_LENGTH_BYTE);
            System.arraycopy(cipherText, 0, encryptedDataWithIv, IV_LENGTH_BYTE, cipherText.length);

            return Optional.of(Base64.getEncoder().encodeToString(encryptedDataWithIv));
        } catch (Exception e) {
            log.error("Encryption failed", e);
            return Optional.empty();
        }
    }

    /**
     * Decrypts data previously encrypted with the encrypt method (expects IV prepended).
     *
     * @param base64EncryptedDataWithIv Base64 encoded string containing (IV + Ciphertext).
     * @return Optional<String> containing the plaintext, or empty on error (e.g., bad key, bad data).
     */
    public Optional<String> decrypt(String base64EncryptedDataWithIv) {
        if (!StringUtils.hasText(base64EncryptedDataWithIv)) return Optional.empty();
        try {
            byte[] decodedData = Base64.getDecoder().decode(base64EncryptedDataWithIv);

            if (decodedData.length < IV_LENGTH_BYTE) {
                log.error("Decryption failed: Input data too short ({}) to contain IV ({} bytes).", decodedData.length, IV_LENGTH_BYTE);
                return Optional.empty();
            }
            // Extract IV (first 12 bytes)
            byte[] iv = new byte[IV_LENGTH_BYTE];
            System.arraycopy(decodedData, 0, iv, 0, IV_LENGTH_BYTE);
            // Extract Ciphertext (the rest)
            byte[] cipherText = new byte[decodedData.length - IV_LENGTH_BYTE];
            System.arraycopy(decodedData, IV_LENGTH_BYTE, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(TAG_LENGTH_BIT, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);

            byte[] plainTextBytes = cipher.doFinal(cipherText);
            return Optional.of(new String(plainTextBytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            // Avoid logging too much detail for security reasons in prod, but helpful for dev
            log.error("Decryption failed (may indicate wrong key, corrupted data, or padding error): {}", e.getMessage());
            log.debug("Decryption exception details", e); // Log stack trace only on debug
            return Optional.empty();
        }
    }
}
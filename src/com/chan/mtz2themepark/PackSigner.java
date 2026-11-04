package com.chan.mtz2themepark;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.android.apksig.ApkSigner;

import java.io.File;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Date;

import javax.security.auth.x500.X500Principal;

/**
 * 用 Android Keystore 里的密钥做 v2 签名。
 * 同一密钥才能覆盖安装生成的图标包；卸载转换应用会丢失密钥，届时要先卸载旧图标包。
 */
final class PackSigner {
    private static final String ALIAS = "icon_pack_signing";

    static void sign(File input, File output, int minSdk) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) generateKey();
        PrivateKey key = (PrivateKey) store.getKey(ALIAS, null);
        X509Certificate cert = (X509Certificate) store.getCertificate(ALIAS);
        ApkSigner.SignerConfig config =
                new ApkSigner.SignerConfig.Builder("pack", key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(config))
                .setInputApk(input)
                .setOutputApk(output)
                .setMinSdkVersion(minSdk)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(false)
                .setAlignmentPreserved(true)
                .build()
                .sign();
    }

    private static void generateKey() throws Exception {
        long now = System.currentTimeMillis();
        KeyPairGenerator gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
        gen.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(new X500Principal("CN=MTZ Icon Pack"))
                .setCertificateSerialNumber(BigInteger.valueOf(now))
                .setCertificateNotBefore(new Date(now - 86400000L))
                .setCertificateNotAfter(new Date(now + 30L * 365 * 86400000L))
                .build());
        gen.generateKeyPair();
    }

    private PackSigner() {}
}

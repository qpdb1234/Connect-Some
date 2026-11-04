package com.limelight.binding.crypto;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Base64;

import com.limelight.LimeLog;
import com.limelight.nvstream.http.LimelightCryptoProvider;

public class AndroidCryptoProvider implements LimelightCryptoProvider {

    private final File certFile;
    private final File keyFile;

    private X509Certificate cert;
    private PrivateKey key;
    private byte[] pemCertBytes;

    private static final Object globalCryptoLock = new Object();

    /**
     * Ceiling for a piece of key material read from disk or from a backup file. A generated
     * 2048-bit pair is a couple of hundred bytes, so anything much larger is a mistake or an
     * attempt to make this class parse a giant blob.
     */
    private static final int MAX_KEY_MATERIAL_BYTES = 64 * 1024;

    private static final Provider bcProvider = new BouncyCastleProvider();

    public AndroidCryptoProvider(Context c) {
        String dataPath = c.getFilesDir().getAbsolutePath();

        certFile = new File(dataPath + File.separator + "client.crt");
        keyFile = new File(dataPath + File.separator + "client.key");
    }

    private byte[] loadFileToBytes(File f) {
        if (!f.exists()) {
            return null;
        }

        // A single read() is allowed to return fewer bytes than asked for, and treating that
        // as a missing file threw away a perfectly good key pair - which silently cost the
        // user their pairing. Read exactly the promised length.
        try (final FileInputStream fin = new FileInputStream(f)) {
            long size = f.length();
            if (size <= 0 || size > MAX_KEY_MATERIAL_BYTES) {
                LimeLog.warning("Implausible key material size: " + size);
                return null;
            }

            // readNBytes() would be the short form, but it is API 32 and we support 26.
            byte[] fileData = new byte[(int) size];
            int offset = 0;
            while (offset < fileData.length) {
                int read = fin.read(fileData, offset, fileData.length - offset);
                if (read < 0)
                    break;
                offset += read;
            }
            if (offset != fileData.length) {
                // Failed to read
                return null;
            }
            return fileData;
        } catch (IOException e) {
            return null;
        }
    }

    private boolean loadCertKeyPair() {
        byte[] certBytes = loadFileToBytes(certFile);
        byte[] keyBytes = loadFileToBytes(keyFile);

        // If either file was missing, we definitely can't succeed
        if (certBytes == null || keyBytes == null) {
            LimeLog.info("Missing cert or key; need to generate a new one");
            return false;
        }

        try {
            CertificateFactory certFactory = CertificateFactory.getInstance("X.509", bcProvider);
            cert = (X509Certificate) certFactory.generateCertificate(new ByteArrayInputStream(certBytes));
            pemCertBytes = certBytes;
            KeyFactory keyFactory = KeyFactory.getInstance("RSA", bcProvider);
            key = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (CertificateException e) {
            // May happen if the cert is corrupt
            LimeLog.warning("Corrupted certificate");
            return false;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        } catch (InvalidKeySpecException e) {
            // May happen if the key is corrupt
            LimeLog.warning("Corrupted key");
            return false;
        }

        return true;
    }

    @SuppressLint("TrulyRandom")
    private boolean generateCertKeyPair() {
        byte[] snBytes = new byte[8];
        new SecureRandom().nextBytes(snBytes);

        KeyPair keyPair;
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA", bcProvider);
            keyPairGenerator.initialize(2048);
            keyPair = keyPairGenerator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        Date now = new Date();

        // Expires in 20 years
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(now);
        calendar.add(Calendar.YEAR, 20);
        Date expirationDate = calendar.getTime();

        BigInteger serial = new BigInteger(snBytes).abs();

        X500NameBuilder nameBuilder = new X500NameBuilder(BCStyle.INSTANCE);
        nameBuilder.addRDN(BCStyle.CN, "NVIDIA GameStream Client");
        X500Name name = nameBuilder.build();

        X509v3CertificateBuilder certBuilder = new X509v3CertificateBuilder(name, serial, now, expirationDate, Locale.ENGLISH, name,
            SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded()));

        try {
            ContentSigner sigGen = new JcaContentSignerBuilder("SHA256withRSA").setProvider(bcProvider).build(keyPair.getPrivate());
            cert = new JcaX509CertificateConverter().setProvider(bcProvider).getCertificate(certBuilder.build(sigGen));
            key = keyPair.getPrivate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        LimeLog.info("Generated a new key pair");

        // Save the resulting pair
        saveCertKeyPair();

        return true;
    }

    private void saveCertKeyPair() {
        try (final FileOutputStream certOut = new FileOutputStream(certFile);
             final FileOutputStream keyOut = new FileOutputStream(keyFile)
        ) {
            // Write the certificate in OpenSSL PEM format (important for the server)
            StringWriter strWriter = new StringWriter();
            try (final JcaPEMWriter pemWriter = new JcaPEMWriter(strWriter)) {
                pemWriter.writeObject(cert);
            }

            // Line endings MUST be UNIX for the PC to accept the cert properly
            try (final OutputStreamWriter certWriter = new OutputStreamWriter(certOut)) {
                String pemStr = strWriter.getBuffer().toString();
                for (int i = 0; i < pemStr.length(); i++) {
                    char c = pemStr.charAt(i);
                    if (c != '\r')
                        certWriter.append(c);
                }
            }

            // Write the private out in PKCS8 format
            keyOut.write(key.getEncoded());

            LimeLog.info("Saved generated key pair to disk");
        } catch (IOException e) {
            // This isn't good because it means we'll have
            // to re-pair next time
            e.printStackTrace();
        }
    }

    /**
     * The identity this client presents to a GameStream host: the PEM certificate and the
     * PKCS8-encoded private key, exactly as they are kept on disk. The two belong together -
     * a host recognises the client by its certificate, and that pairing is only usable by
     * whoever also holds the matching key.
     */
    public static class ClientIdentity {
        public final byte[] certificatePem;
        public final byte[] privateKeyPkcs8;

        public ClientIdentity(byte[] certificatePem, byte[] privateKeyPkcs8) {
            this.certificatePem = certificatePem;
            this.privateKeyPkcs8 = privateKeyPkcs8;
        }
    }

    /**
     * Reads the stored identity for a backup. Nothing is generated here: a device that has
     * never paired has no identity worth carrying over, and inventing one would put a private
     * key that stands for nothing into the user's backup file.
     */
    public ClientIdentity exportClientIdentity() {
        synchronized (globalCryptoLock) {
            byte[] certBytes = loadFileToBytes(certFile);
            byte[] keyBytes = loadFileToBytes(keyFile);
            if (certBytes == null || keyBytes == null) {
                return null;
            }
            return new ClientIdentity(certBytes, keyBytes);
        }
    }

    /**
     * Installs an identity read back from a backup, so that a reinstalled app walks into the
     * pairings it had instead of asking the host for a new PIN.
     *
     * The material is parsed, and checked against itself, before a single byte is written.
     * Junk on disk is worse than no file at all: the next reader cannot tell a bad import
     * from a corrupt installation, regenerates a key pair, and the pairing that this backup
     * was restored to keep is gone either way.
     */
    public boolean importClientIdentity(ClientIdentity identity) {
        if (identity == null) {
            return false;
        }
        if (identity.certificatePem == null || identity.certificatePem.length == 0
                || identity.certificatePem.length > MAX_KEY_MATERIAL_BYTES
                || identity.privateKeyPkcs8 == null || identity.privateKeyPkcs8.length == 0
                || identity.privateKeyPkcs8.length > MAX_KEY_MATERIAL_BYTES) {
            LimeLog.warning("Rejected a backup client identity of implausible size");
            return false;
        }

        synchronized (globalCryptoLock) {
            X509Certificate parsedCert;
            PrivateKey parsedKey;
            try {
                parsedCert = (X509Certificate) CertificateFactory.getInstance("X.509", bcProvider)
                        .generateCertificate(new ByteArrayInputStream(identity.certificatePem));
                parsedCert.checkValidity();
                parsedKey = KeyFactory.getInstance("RSA", bcProvider)
                        .generatePrivate(new PKCS8EncodedKeySpec(identity.privateKeyPkcs8));
            } catch (CertificateException | InvalidKeySpecException e) {
                LimeLog.warning("Backup contains unreadable client credentials: " + e);
                return false;
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }

            // A certificate and a key can each be perfectly readable and still come from
            // different pairs. The host then refuses the TLS handshake - and every restored
            // server fails on it, with nothing pointing back to this import.
            if (parsedCert.getPublicKey() instanceof RSAPublicKey
                    && parsedKey instanceof RSAPrivateKey) {
                RSAPublicKey publicPart = (RSAPublicKey) parsedCert.getPublicKey();
                RSAPrivateKey privatePart = (RSAPrivateKey) parsedKey;
                if (!publicPart.getModulus().equals(privatePart.getModulus())) {
                    LimeLog.warning("Backup client certificate and private key are not a pair");
                    return false;
                }
                // The exponent only comes along when the key carries the full CRT form, which
                // is what this class writes; a bare RSAPrivateKey has nothing to compare.
                if (privatePart instanceof RSAPrivateCrtKey
                        && !publicPart.getPublicExponent().equals(((RSAPrivateCrtKey) privatePart).getPublicExponent())) {
                    LimeLog.warning("Backup client certificate and private key disagree");
                    return false;
                }
            }

            try (final FileOutputStream certOut = new FileOutputStream(certFile);
                 final FileOutputStream keyOut = new FileOutputStream(keyFile)
            ) {
                certOut.write(identity.certificatePem);
                keyOut.write(identity.privateKeyPkcs8);
            } catch (IOException e) {
                LimeLog.warning("Could not store the restored client identity: " + e);
                return false;
            }

            // These fields are this instance's cache, so they have to become the restored
            // pair as well - otherwise the new identity stays invisible until the process
            // happens to die, which on a device that is never rebooted can be a long time.
            cert = parsedCert;
            key = parsedKey;
            pemCertBytes = identity.certificatePem;
            LimeLog.info("Restored the client identity from a backup");
            return true;
        }
    }

    public X509Certificate getClientCertificate() {
        // Use a lock here to ensure only one guy will be generating or loading
        // the certificate and key at a time
        synchronized (globalCryptoLock) {
            // Return a loaded cert if we have one
            if (cert != null) {
                return cert;
            }

            // No loaded cert yet, let's see if we have one on disk
            if (loadCertKeyPair()) {
                // Got one
                return cert;
            }

            // Try to generate a new key pair
            if (!generateCertKeyPair()) {
                // Failed
                return null;
            }

            // Load the generated pair
            loadCertKeyPair();
            return cert;
        }
    }

    public PrivateKey getClientPrivateKey() {
        // Use a lock here to ensure only one guy will be generating or loading
        // the certificate and key at a time
        synchronized (globalCryptoLock) {
            // Return a loaded key if we have one
            if (key != null) {
                return key;
            }

            // No loaded key yet, let's see if we have one on disk
            if (loadCertKeyPair()) {
                // Got one
                return key;
            }

            // Try to generate a new key pair
            if (!generateCertKeyPair()) {
                // Failed
                return null;
            }

            // Load the generated pair
            loadCertKeyPair();
            return key;
        }
    }

    public byte[] getPemEncodedClientCertificate() {
        synchronized (globalCryptoLock) {
            // Call our helper function to do the cert loading/generation for us
            getClientCertificate();

            // Return a cached value if we have it
            return pemCertBytes;
        }
    }

    @Override
    public String encodeBase64String(byte[] data) {
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }
}

package com.limelight.nvstream.http;

import org.bouncycastle.crypto.BlockCipher;
import org.bouncycastle.crypto.engines.AESLightEngine;
import org.bouncycastle.crypto.params.KeyParameter;

import org.xmlpull.v1.XmlPullParserException;

import com.limelight.LimeLog;

import java.security.cert.Certificate;
import java.io.*;
import java.security.*;
import java.security.cert.*;
import java.util.Arrays;
import java.util.Locale;

public class PairingManager {

    private NvHTTP http;
    
    private PrivateKey pk;
    private X509Certificate cert;
    private byte[] pemCertBytes;

    private X509Certificate serverCert;
    
    public enum PairState {
        NOT_PAIRED,
        PAIRED,
        PIN_WRONG,
        FAILED,
        ALREADY_IN_PROGRESS
    }
    
    public PairingManager(NvHTTP http, LimelightCryptoProvider cryptoProvider) {
        this.http = http;
        this.cert = cryptoProvider.getClientCertificate();
        this.pemCertBytes = cryptoProvider.getPemEncodedClientCertificate();
        this.pk = cryptoProvider.getClientPrivateKey();
    }
    
    final private static char[] hexArray = "0123456789ABCDEF".toCharArray();
    private static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for ( int j = 0; j < bytes.length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }
    
    private static byte[] hexToBytes(String s) throws IOException {
        int len = s.length();
        if (len % 2 != 0) {
            throw new IOException("Illegal string length: "+len);
        }

        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            // Character.digit() answers -1 for anything that is not a hex digit, which
            // would be quietly folded into the result as 0xFF... These strings come off
            // the host's XML, so "not hex" has to be a refused response rather than a
            // key, a challenge or a certificate made of whatever the junk decoded to.
            int high = Character.digit(s.charAt(i), 16);
            int low = Character.digit(s.charAt(i + 1), 16);
            if (high < 0 || low < 0) {
                throw new IOException("Illegal character in host response at "+i);
            }
            data[i / 2] = (byte) ((high << 4) + low);
        }
        return data;
    }
    
    private X509Certificate extractPlainCert(String text) throws XmlPullParserException, IOException
    {
        // Plaincert may be null if another client is already trying to pair
        String certText = NvHTTP.getXmlString(text, "plaincert", false);
        if (certText != null) {
            byte[] certBytes = hexToBytes(certText);

            try {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                return (X509Certificate)cf.generateCertificate(new ByteArrayInputStream(certBytes));
            } catch (CertificateException e) {
                // A host that answers with something that is not a certificate is a
                // failure of the pairing, not of this program: report it the way the
                // caller already reports a pairing that went wrong.
                throw new IOException("Host sent an unreadable certificate", e);
            }
        }
        else {
            return null;
        }
    }
    
    private byte[] generateRandomBytes(int length)
    {
        byte[] rand = new byte[length];
        new SecureRandom().nextBytes(rand);
        return rand;
    }
    
    private static byte[] saltPin(byte[] salt, String pin) throws UnsupportedEncodingException {
        // Sized from the encoded bytes, not the character count: the arraycopy below moves
        // as many bytes as UTF-8 produced, so a PIN that is not plain ASCII would otherwise
        // run past the end of the array.
        byte[] pinBytes = pin.getBytes("UTF-8");
        byte[] saltedPin = new byte[salt.length + pinBytes.length];
        System.arraycopy(salt, 0, saltedPin, 0, salt.length);
        System.arraycopy(pinBytes, 0, saltedPin, salt.length, pinBytes.length);
        return saltedPin;
    }

    private static Signature getSha256SignatureInstanceForKey(Key key) throws NoSuchAlgorithmException {
        switch (key.getAlgorithm()) {
            case "RSA":
                return Signature.getInstance("SHA256withRSA");
            case "EC":
                return Signature.getInstance("SHA256withECDSA");
            default:
                throw new NoSuchAlgorithmException("Unhandled key algorithm: " + key.getAlgorithm());
        }
    }
    
    private static boolean verifySignature(byte[] data, byte[] signature, Certificate cert) {
        try {
            Signature sig = PairingManager.getSha256SignatureInstanceForKey(cert.getPublicKey());
            sig.initVerify(cert.getPublicKey());
            sig.update(data);
            return sig.verify(signature);
        } catch (NoSuchAlgorithmException | SignatureException | InvalidKeyException e) {
            // Every one of these arguments - the algorithm named by the certificate, the
            // signature the host chose to send - is the host's, not ours. A provider
            // refusing an empty or malformed signature is the same answer as a signature
            // that does not check: this is not the server we paired with.
            LimeLog.warning("Signature verification failed: " + e.getMessage());
            return false;
        }
    }
    
    private static byte[] signData(byte[] data, PrivateKey key) {
        try {
            Signature sig = PairingManager.getSha256SignatureInstanceForKey(key);
            sig.initSign(key);
            sig.update(data);
            return sig.sign();
        } catch (NoSuchAlgorithmException | SignatureException | InvalidKeyException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    private static byte[] performBlockCipher(BlockCipher blockCipher, byte[] input) {
        int blockSize = blockCipher.getBlockSize();
        int blockRoundedSize = (input.length + (blockSize - 1)) & ~(blockSize - 1);

        byte[] blockRoundedInputData = Arrays.copyOf(input, blockRoundedSize);
        byte[] blockRoundedOutputData = new byte[blockRoundedSize];

        for (int offset = 0; offset < blockRoundedSize; offset += blockSize) {
            blockCipher.processBlock(blockRoundedInputData, offset, blockRoundedOutputData, offset);
        }

        return blockRoundedOutputData;
    }
    
    private static byte[] decryptAes(byte[] encryptedData, byte[] aesKey) {
        BlockCipher aesEngine = new AESLightEngine();
        aesEngine.init(false, new KeyParameter(aesKey));
        return performBlockCipher(aesEngine, encryptedData);
    }
    
    private static byte[] encryptAes(byte[] plaintextData, byte[] aesKey) {
        BlockCipher aesEngine = new AESLightEngine();
        aesEngine.init(true, new KeyParameter(aesKey));
        return performBlockCipher(aesEngine, plaintextData);
    }
    
    private static byte[] generateAesKey(PairingHashAlgorithm hashAlgo, byte[] keyData) {
        return Arrays.copyOf(hashAlgo.hashData(keyData), 16);
    }
    
    private static byte[] concatBytes(byte[] a, byte[] b) {
        byte[] c = new byte[a.length + b.length];
        System.arraycopy(a, 0, c, 0, a.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }
    
    public static String generatePinString() {
        SecureRandom r = new SecureRandom();
        return String.format((Locale)null, "%d%d%d%d",
                r.nextInt(10), r.nextInt(10),
                r.nextInt(10), r.nextInt(10));
    }

    public X509Certificate getPairedCert() {
        return serverCert;
    }
    
    public PairState pair(String serverInfo, String pin) throws IOException, XmlPullParserException {
        PairingHashAlgorithm hashAlgo;

        int serverMajorVersion = http.getServerMajorVersion(serverInfo);
        LimeLog.info("Pairing with server generation: "+serverMajorVersion);
        if (serverMajorVersion >= 7) {
            // Gen 7+ uses SHA-256 hashing
            hashAlgo = new Sha256PairingHash();
        }
        else {
            // Prior to Gen 7, SHA-1 is used
            hashAlgo = new Sha1PairingHash();
        }
        
        // Generate a salt for hashing the PIN
        byte[] salt = generateRandomBytes(16);

        // Combine the salt and pin, then create an AES key from them
        byte[] aesKey = generateAesKey(hashAlgo, saltPin(salt, pin));
        
        // Send the salt and get the server cert. This doesn't have a read timeout
        // because the user must enter the PIN before the server responds
        String getCert = http.executePairingCommand("phrase=getservercert&salt="+
                bytesToHex(salt)+"&clientcert="+bytesToHex(pemCertBytes),
                false);
        if (!NvHTTP.getXmlString(getCert, "paired", true).equals("1")) {
            return PairState.FAILED;
        }

        // Save this cert for retrieval later
        serverCert = extractPlainCert(getCert);
        if (serverCert == null) {
            // Attempting to pair while another device is pairing will cause GFE
            // to give an empty cert in the response.
            http.unpair();
            return PairState.ALREADY_IN_PROGRESS;
        }

        // Require this cert for TLS to this host
        http.setServerCert(serverCert);
        
        // Generate a random challenge and encrypt it with our AES key
        byte[] randomChallenge = generateRandomBytes(16);
        byte[] encryptedChallenge = encryptAes(randomChallenge, aesKey);
        
        // Send the encrypted challenge to the server
        String challengeResp = http.executePairingCommand("clientchallenge="+bytesToHex(encryptedChallenge), true);
        if (!NvHTTP.getXmlString(challengeResp, "paired", true).equals("1")) {
            http.unpair();
            return PairState.FAILED;
        }
        
        // Decode the server's response and subsequent challenge
        byte[] encServerChallengeResponse = hexToBytes(NvHTTP.getXmlString(challengeResp, "challengeresponse", true));
        byte[] decServerChallengeResponse = decryptAes(encServerChallengeResponse, aesKey);

        // The host decides how long that field was. copyOfRange() answers a range past
        // the end of the array with an unchecked ArrayIndexOutOfBoundsException, which
        // would take the pairing UI down instead of reporting a failed pairing, so the
        // shape is checked before it is sliced.
        int requiredChallengeBytes = hashAlgo.getHashLength() + 16;
        if (decServerChallengeResponse.length < requiredChallengeBytes) {
            http.unpair();
            throw new IOException("Host sent a truncated challenge response: " +
                    decServerChallengeResponse.length + " of " + requiredChallengeBytes + " bytes");
        }

        byte[] serverResponse = Arrays.copyOfRange(decServerChallengeResponse, 0, hashAlgo.getHashLength());
        byte[] serverChallenge = Arrays.copyOfRange(decServerChallengeResponse, hashAlgo.getHashLength(), requiredChallengeBytes);
        
        // Using another 16 bytes secret, compute a challenge response hash using the secret, our cert sig, and the challenge
        byte[] clientSecret = generateRandomBytes(16);
        byte[] challengeRespHash = hashAlgo.hashData(concatBytes(concatBytes(serverChallenge, cert.getSignature()), clientSecret));
        byte[] challengeRespEncrypted = encryptAes(challengeRespHash, aesKey);
        String secretResp = http.executePairingCommand("serverchallengeresp="+bytesToHex(challengeRespEncrypted), true);
        if (!NvHTTP.getXmlString(secretResp, "paired", true).equals("1")) {
            http.unpair();
            return PairState.FAILED;
        }
        
        // Get the server's signed secret
        byte[] serverSecretResp = hexToBytes(NvHTTP.getXmlString(secretResp, "pairingsecret", true));
        // Same reasoning as the challenge: a host that sends fewer than 16 bytes would
        // make the slice throw, and one that sends no signature at all has nothing to
        // authenticate itself with.
        if (serverSecretResp.length < 17) {
            http.unpair();
            throw new IOException("Host sent a truncated pairing secret: " + serverSecretResp.length + " bytes");
        }
        byte[] serverSecret = Arrays.copyOfRange(serverSecretResp, 0, 16);
        byte[] serverSignature = Arrays.copyOfRange(serverSecretResp, 16, serverSecretResp.length);

        // Ensure the authenticity of the data
        if (!verifySignature(serverSecret, serverSignature, serverCert)) {
            // Cancel the pairing process
            http.unpair();
            
            // Looks like a MITM
            return PairState.FAILED;
        }
        
        // Ensure the server challenge matched what we expected (aka the PIN was correct)
        byte[] serverChallengeRespHash = hashAlgo.hashData(concatBytes(concatBytes(randomChallenge, serverCert.getSignature()), serverSecret));
        if (!Arrays.equals(serverChallengeRespHash, serverResponse)) {
            // Cancel the pairing process
            http.unpair();
            
            // Probably got the wrong PIN
            return PairState.PIN_WRONG;
        }
        
        // Send the server our signed secret
        byte[] clientPairingSecret = concatBytes(clientSecret, signData(clientSecret, pk));
        String clientSecretResp = http.executePairingCommand("clientpairingsecret="+bytesToHex(clientPairingSecret), true);
        if (!NvHTTP.getXmlString(clientSecretResp, "paired", true).equals("1")) {
            http.unpair();
            return PairState.FAILED;
        }
        
        // Do the initial challenge (seems necessary for us to show as paired)
        String pairChallenge = http.executePairingChallenge();
        if (!NvHTTP.getXmlString(pairChallenge, "paired", true).equals("1")) {
            http.unpair();
            return PairState.FAILED;
        }

        return PairState.PAIRED;
    }
    
    private interface PairingHashAlgorithm {
        int getHashLength();
        byte[] hashData(byte[] data);
    }
    
    private static class Sha1PairingHash implements PairingHashAlgorithm {
        public int getHashLength() {
            return 20;
        }
        
        public byte[] hashData(byte[] data) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                return md.digest(data);
            }
            catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }
    }
    
    private static class Sha256PairingHash implements PairingHashAlgorithm {
        public int getHashLength() {
            return 32;
        }
        
        public byte[] hashData(byte[] data) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                return md.digest(data);
            }
            catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }
    }
}

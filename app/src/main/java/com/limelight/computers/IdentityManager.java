package com.limelight.computers;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.security.SecureRandom;
import java.util.Locale;

import com.limelight.LimeLog;

import android.content.Context;

public class IdentityManager {
    private static final String UNIQUE_ID_FILE_NAME = "uniqueid";
    private static final int UID_SIZE_IN_BYTES = 8;

    private String uniqueId;

    public IdentityManager(Context c) {
        uniqueId = loadUniqueId(c);
        if (uniqueId == null) {
            uniqueId = generateNewUniqueId(c);
        }

        LimeLog.info("UID is now: "+uniqueId);
    }

    public String getUniqueId() {
        return uniqueId;
    }

    private static String loadUniqueId(Context c) {
        // 2 Hex digits per byte
        char[] uid = new char[UID_SIZE_IN_BYTES * 2];
        LimeLog.info("Reading UID from disk");
        try (final InputStreamReader reader =
                     new InputStreamReader(c.openFileInput(UNIQUE_ID_FILE_NAME))
        ) {
            // read() is not promised to fill the array - a short read here used to be
            // taken for a corrupt file, and the recovery was to throw the identity away
            // and generate a new one, which silently breaks every pairing made against
            // the old id. Keep reading until the expected count is in hand.
            int read = 0;
            while (read < uid.length) {
                int got = reader.read(uid, read, uid.length - read);
                if (got < 0)
                    break;
                read += got;
            }
            if (read != UID_SIZE_IN_BYTES * 2) {
                LimeLog.severe("UID file data is truncated");
                return null;
            }
            return new String(uid);
        } catch (FileNotFoundException e) {
            LimeLog.info("No UID file found");
            return null;
        } catch (IOException e) {
            LimeLog.severe("Error while reading UID file");
            e.printStackTrace();
            return null;
        }
    }

    private static String generateNewUniqueId(Context c) {
        // Generate a new UID hex string. This id is what the host recognises this client
        // by, so it has to be unguessable by anything else on the device - a seeded
        // java.util.Random is not.
        LimeLog.info("Generating new UID");
        String uidStr = String.format((Locale) null, "%016x", new SecureRandom().nextLong());

        try (final OutputStreamWriter writer =
                     new OutputStreamWriter(c.openFileOutput(UNIQUE_ID_FILE_NAME, 0))
        ) {
            writer.write(uidStr);
            LimeLog.info("UID written to disk");
        } catch (IOException e) {
            LimeLog.severe("Error while writing UID file");
            e.printStackTrace();
        }

        // We can return a UID even if I/O fails
        return uidStr;
    }
}

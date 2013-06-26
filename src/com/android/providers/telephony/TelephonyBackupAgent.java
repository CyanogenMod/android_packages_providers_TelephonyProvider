/*
 * Copyright (C) 2009 The Android Open Source Project
 * Copyright (C) 2013 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.telephony;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.app.backup.BackupAgentHelper;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.Telephony.Blacklist;
import android.text.TextUtils;
import android.util.Log;

/**
 * Performs backup and restore of the telephony data.
 * At the moment this is restricted to the blacklist data.
 */
public class TelephonyBackupAgent extends BackupAgentHelper {
    private static final String TAG = "TelephonyBackupAgent";

    private static final String KEY_BLACKLIST = "blacklist";

    private static final int STATE_BLACKLIST = 0;
    private static final int STATE_SIZE = 1;

    private static final String SEPARATOR = "|";

    private static final byte[] EMPTY_DATA = new byte[0];

    private static final int COLUMN_NUMBER = 0;
    private static final int COLUMN_PHONE_MODE = 1;
    private static final int COLUMN_MESSAGE_MODE = 2;

    private static final String[] PROJECTION = {
        Blacklist.NUMBER,
        Blacklist.PHONE_MODE,
        Blacklist.MESSAGE_MODE
    };

    @Override
    public void onBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
            ParcelFileDescriptor newState) throws IOException {
        byte[] blacklistData = getBlacklist();
        long[] stateChecksums = readOldChecksums(oldState);

        stateChecksums[STATE_BLACKLIST] =
                writeIfChanged(stateChecksums[STATE_BLACKLIST], KEY_BLACKLIST,
                        blacklistData, data);

        writeNewChecksums(stateChecksums, newState);
    }

    @Override
    public void onRestore(BackupDataInput data, int appVersionCode,
            ParcelFileDescriptor newState) throws IOException {
        while (data.readNextHeader()) {
            final String key = data.getKey();
            final int size = data.getDataSize();
            if (KEY_BLACKLIST.equals(key)) {
                restoreBlacklist(data);
            } else {
                data.skipEntityData();
            }
        }
    }

    private long[] readOldChecksums(ParcelFileDescriptor oldState) throws IOException {
        long[] stateChecksums = new long[STATE_SIZE];

        DataInputStream dataInput = new DataInputStream(
                new FileInputStream(oldState.getFileDescriptor()));
        for (int i = 0; i < STATE_SIZE; i++) {
            try {
                stateChecksums[i] = dataInput.readLong();
            } catch (EOFException eof) {
                break;
            }
        }
        dataInput.close();
        return stateChecksums;
    }

    private void writeNewChecksums(long[] checksums, ParcelFileDescriptor newState)
            throws IOException {
        DataOutputStream dataOutput = new DataOutputStream(
                new FileOutputStream(newState.getFileDescriptor()));
        for (int i = 0; i < STATE_SIZE; i++) {
            dataOutput.writeLong(checksums[i]);
        }
        dataOutput.close();
    }

    private long writeIfChanged(long oldChecksum, String key, byte[] data,
            BackupDataOutput output) {
        CRC32 checkSummer = new CRC32();
        checkSummer.update(data);
        long newChecksum = checkSummer.getValue();
        if (oldChecksum == newChecksum) {
            return oldChecksum;
        }
        try {
            output.writeEntityHeader(key, data.length);
            output.writeEntityData(data, data.length);
        } catch (IOException ioe) {
            // Bail
        }
        return newChecksum;
    }

    private byte[] getBlacklist() {
        Cursor cursor = getContentResolver().query(Blacklist.CONTENT_URI, PROJECTION,
                null, null, Blacklist.DEFAULT_SORT_ORDER);
        if (cursor == null) {
            return EMPTY_DATA;
        }
        if (!cursor.moveToFirst()) {
            Log.e(TAG, "Couldn't read from the cursor");
            cursor.close();
            return EMPTY_DATA;
        }

        byte[] sizeBytes = new byte[4];
        ByteArrayOutputStream baos = new ByteArrayOutputStream(cursor.getCount() * 20);
        try {
            GZIPOutputStream gzip = new GZIPOutputStream(baos);
            while (!cursor.isAfterLast()) {
                String number = cursor.getString(COLUMN_NUMBER);
                int phoneMode = cursor.getInt(COLUMN_PHONE_MODE);
                int messageMode = cursor.getInt(COLUMN_MESSAGE_MODE);
                // TODO: escape the string
                String out = number + SEPARATOR + phoneMode + SEPARATOR + messageMode;
                byte[] line = out.getBytes();
                writeInt(sizeBytes, 0, line.length);
                gzip.write(sizeBytes);
                gzip.write(line);
                cursor.moveToNext();
            }
            gzip.finish();
        } catch (IOException ioe) {
            Log.e(TAG, "Couldn't compress the blacklist", ioe);
            return EMPTY_DATA;
        } finally {
            cursor.close();
        }
        return baos.toByteArray();
    }

    private void restoreBlacklist(BackupDataInput data) {
        ContentValues cv = new ContentValues(2);
        byte[] blacklistCompressed = new byte[data.getDataSize()];
        byte[] blacklist = null;
        try {
            data.readEntityData(blacklistCompressed, 0, blacklistCompressed.length);
            GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(blacklistCompressed));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] tempData = new byte[1024];
            int got;
            while ((got = gzip.read(tempData)) > 0) {
                baos.write(tempData, 0, got);
            }
            gzip.close();
            blacklist = baos.toByteArray();
        } catch (IOException ioe) {
            Log.e(TAG, "Couldn't read and uncompress entity data", ioe);
            return;
        }

        int pos = 0;
        while (pos + 4 < blacklist.length) {
            int length = readInt(blacklist, pos);
            pos += 4;
            if (pos + length > blacklist.length) {
                Log.e(TAG, "Insufficient data");
            }
            String line = new String(blacklist, pos, length);
            pos += length;
            // TODO: unescape the string
            StringTokenizer st = new StringTokenizer(line, SEPARATOR);
            try {
                String number = st.nextToken();
                int phoneMode = Integer.parseInt(st.nextToken());
                int messageMode = Integer.parseInt(st.nextToken());

                if (!TextUtils.isEmpty(number)) {
                    cv.clear();
                    cv.put(Blacklist.NUMBER, number);
                    cv.put(Blacklist.PHONE_MODE, phoneMode);
                    cv.put(Blacklist.MESSAGE_MODE, messageMode);

                    Uri uri = Blacklist.CONTENT_FILTER_BYNUMBER_URI.buildUpon()
                            .appendPath(number).build();
                    getContentResolver().update(uri, cv, null, null);
                }
            } catch (NoSuchElementException nsee) {
                Log.e(TAG, "Token format error\n" + nsee);
            } catch (NumberFormatException nfe) {
                Log.e(TAG, "Number format error\n" + nfe);
            }
        }
    }

    /**
     * Write an int in BigEndian into the byte array.
     * @param out byte array
     * @param pos current pos in array
     * @param value integer to write
     * @return the index after adding the size of an int (4)
     */
    private int writeInt(byte[] out, int pos, int value) {
        out[pos + 0] = (byte) ((value >> 24) & 0xFF);
        out[pos + 1] = (byte) ((value >> 16) & 0xFF);
        out[pos + 2] = (byte) ((value >>  8) & 0xFF);
        out[pos + 3] = (byte) ((value >>  0) & 0xFF);
        return pos + 4;
    }

    private int readInt(byte[] in, int pos) {
        int result =
                ((in[pos    ] & 0xFF) << 24) |
                ((in[pos + 1] & 0xFF) << 16) |
                ((in[pos + 2] & 0xFF) <<  8) |
                ((in[pos + 3] & 0xFF) <<  0);
        return result;
    }
}

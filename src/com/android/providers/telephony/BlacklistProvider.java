/*
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

import android.app.backup.BackupManager;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.provider.Telephony.Blacklist;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;

public class BlacklistProvider extends ContentProvider {
    private static final String TAG = "BlacklistProvider";
    private static final boolean DEBUG = true;

    private static final String DATABASE_NAME = "blacklist.db";
    private static final int DATABASE_VERSION = 3;

    private static final String BLACKLIST_TABLE = "blacklist";
    private static final String COLUMN_NORMALIZED = "normalized_number";

    private static final int BL_ALL         = 0;
    private static final int BL_ID          = 1;
    private static final int BL_NUMBER      = 2;
    private static final int BL_PHONE       = 3;
    private static final int BL_MESSAGE     = 4;

    private static final UriMatcher
            sURIMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        sURIMatcher.addURI("blacklist", null,         BL_ALL);
        sURIMatcher.addURI("blacklist", "#",          BL_ID);
        sURIMatcher.addURI("blacklist", "bynumber/*", BL_NUMBER);
        sURIMatcher.addURI("blacklist", "phone",      BL_PHONE);
        sURIMatcher.addURI("blacklist", "message",    BL_MESSAGE);
    }

    private static class DatabaseHelper extends SQLiteOpenHelper {
        // Context to access resources with
        private Context mContext;

        public DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
            mContext = context;
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            // Set up the database schema
            db.execSQL("CREATE TABLE " + BLACKLIST_TABLE +
                "(_id INTEGER PRIMARY KEY," +
                    "number TEXT," +
                    "normalized_number TEXT," +
                    "is_regex INTEGER," +
                    "phone INTEGER DEFAULT 0," +
                    "message INTEGER DEFAULT 0);");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                // drop the uniqueness constraint that was present on the DB in V1
                db.execSQL("ALTER TABLE " + BLACKLIST_TABLE +
                        " RENAME TO " + BLACKLIST_TABLE + "_old;");
                onCreate(db);
                db.execSQL("INSERT INTO " + BLACKLIST_TABLE +
                        " SELECT * FROM " + BLACKLIST_TABLE + "_old;");
            }

            if (oldVersion < 3) {
                // update the normalized number column, v1 and v2 didn't handle
                // alphanumeric 'numbers' correctly

                Cursor rows = db.query(BLACKLIST_TABLE,
                        new String[] { Blacklist._ID, Blacklist.NUMBER },
                        null, null, null, null, null);

                try {
                    db.beginTransaction();
                    if (rows != null) {
                        ContentValues cv = new ContentValues();
                        String[] rowId = new String[1];

                        while (rows.moveToNext()) {
                            rowId[0] = rows.getString(0);
                            cv.clear();
                            cv.put(COLUMN_NORMALIZED, normalizeNumber(rows.getString(1)));
                            db.update(BLACKLIST_TABLE, cv, Blacklist._ID + "= ?", rowId);
                        }
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                    if (rows != null) {
                        rows.close();
                    }
                }
            }
        }
    }

    private DatabaseHelper mOpenHelper;
    private BackupManager mBackupManager;

    @Override
    public boolean onCreate() {
        mOpenHelper = new DatabaseHelper(getContext());
        mBackupManager = new BackupManager(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();

        qb.setTables(BLACKLIST_TABLE);

        // Generate the body of the query.
        int match = sURIMatcher.match(uri);
        if (DEBUG) {
            Log.v(TAG, "Query uri=" + uri + ", match=" + match);
        }

        switch (match) {
            case BL_ALL:
                break;
            case BL_ID:
                qb.appendWhere(Blacklist._ID + " = " + uri.getLastPathSegment());
                break;
            case BL_NUMBER: {
                String number = normalizeNumber(uri.getLastPathSegment());
                boolean regex = uri.getBooleanQueryParameter(Blacklist.REGEX_KEY, false);

                if (regex) {
                    qb.appendWhere("\"" + number + "\" like " + COLUMN_NORMALIZED);
                } else {
                    qb.appendWhere(COLUMN_NORMALIZED + " = \"" + number + "\"");
                }
                break;
            }
            case BL_PHONE:
                qb.appendWhere(Blacklist.PHONE_MODE + " != 0");
                break;
            case BL_MESSAGE:
                qb.appendWhere(Blacklist.MESSAGE_MODE + " != 0");
                break;
            default:
                Log.e(TAG, "query: invalid request: " + uri);
                return null;
        }

        if (TextUtils.isEmpty(sortOrder)) {
            sortOrder = Blacklist.DEFAULT_SORT_ORDER;
        }

        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        Cursor ret = qb.query(db, projection, selection, selectionArgs, null, null, sortOrder);

        ret.setNotificationUri(getContext().getContentResolver(), uri);

        return ret;
    }

    @Override
    public String getType(Uri uri) {
        switch (sURIMatcher.match(uri)) {
            case BL_ALL:
            case BL_PHONE:
            case BL_MESSAGE:
                return "vnd.android.cursor.dir/blacklist-entry";
            case BL_ID:
            case BL_NUMBER:
                return "vnd.android.cursor.item/blacklist-entry";
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues initialValues) {
        int match = sURIMatcher.match(uri);
        if (DEBUG) {
            Log.v(TAG, "Insert uri=" + uri + ", match=" + match);
        }

        if (match != BL_ALL) {
            return null;
        }

        ContentValues values = validateAndPrepareContentValues(initialValues, null);
        if (values == null) {
            Log.e(TAG, "Invalid insert values " + initialValues);
            return null;
        }

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        long rowID = db.insert(BLACKLIST_TABLE, null, values);
        if (rowID <= 0) {
            return null;
        }

        if (DEBUG) Log.d(TAG, "inserted " + values + " rowID = " + rowID);
        notifyChange();

        return ContentUris.withAppendedId(Blacklist.CONTENT_URI, rowID);
    }

    @Override
    public int delete(Uri uri, String where, String[] whereArgs) {
        int count = 0;
        int match = sURIMatcher.match(uri);
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        if (DEBUG) {
            Log.v(TAG, "Delete uri=" + uri + ", match=" + match);
        }

        switch (match) {
            case BL_ALL:
                break;
            case BL_ID:
                if (where != null || whereArgs != null) {
                    throw new UnsupportedOperationException(
                            "Cannot delete URI " + uri + " with a where clause");
                }
                where = Blacklist._ID + " = ?";
                whereArgs = new String[] { uri.getLastPathSegment() };
                break;
            case BL_NUMBER:
                if (where != null || whereArgs != null) {
                    throw new UnsupportedOperationException(
                            "Cannot delete URI " + uri + " with a where clause");
                }
                where = COLUMN_NORMALIZED + " = ?";
                whereArgs = new String[] { normalizeNumber(uri.getLastPathSegment()) };
                break;
            default:
                throw new UnsupportedOperationException("Cannot delete that URI: " + uri);
        }

        count = db.delete(BLACKLIST_TABLE, where, whereArgs);
        if (DEBUG) Log.d(TAG, "delete result count " + count);

        if (count > 0) {
            notifyChange();
        }

        return count;
    }

    @Override
    public int update(Uri uri, ContentValues initialValues, String where, String[] whereArgs) {
        int count = 0;
        int match = sURIMatcher.match(uri);
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        if (DEBUG) {
            Log.v(TAG, "Update uri=" + uri + ", match=" + match);
        }

        String uriNumber = match == BL_NUMBER ? uri.getLastPathSegment() : null;
        ContentValues values = validateAndPrepareContentValues(initialValues, uriNumber);
        if (values == null) {
            Log.e(TAG, "Invalid update values " + initialValues);
            return 0;
        }

        switch (match) {
            case BL_ALL:
                count = db.update(BLACKLIST_TABLE, values, where, whereArgs);
                break;
            case BL_NUMBER:
                if (where != null || whereArgs != null) {
                    throw new UnsupportedOperationException(
                            "Cannot update URI " + uri + " with a where clause");
                }
                db.beginTransaction();
                try {
                    count = db.update(BLACKLIST_TABLE, values, COLUMN_NORMALIZED + " = ?",
                            new String[] { normalizeNumber(uriNumber) });
                    if (count == 0) {
                        // convenience: fall back to insert if number wasn't present
                        if (db.insert(BLACKLIST_TABLE, null, values) > 0) {
                            count = 1;
                        }
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
                break;
            case BL_ID:
                if (where != null || whereArgs != null) {
                    throw new UnsupportedOperationException(
                            "Cannot update URI " + uri + " with a where clause");
                }
                count = db.update(BLACKLIST_TABLE, values, Blacklist._ID + " = ?",
                        new String[] { uri.getLastPathSegment() });
                break;
            default:
                throw new UnsupportedOperationException("Cannot update that URI: " + uri);
        }

        if (DEBUG) Log.d(TAG, "Update result count " + count);

        if (count > 0) {
            notifyChange();
        }

        return count;
    }

    private ContentValues validateAndPrepareContentValues(
            ContentValues initialValues, String uriNumber) {
        ContentValues values = new ContentValues(initialValues);

        // apps are not supposed to mess with the normalized number or the regex state
        values.remove(COLUMN_NORMALIZED);
        values.remove(Blacklist.IS_REGEX);

        // on 'upsert', insert the number passed via URI if no other number was specified
        if (uriNumber != null && !values.containsKey(Blacklist.NUMBER)) {
            values.put(Blacklist.NUMBER, uriNumber);
        }

        if (values.containsKey(Blacklist.NUMBER)) {
            String number = values.getAsString(Blacklist.NUMBER);
            if (TextUtils.isEmpty(number)) {
                return null;
            }

            String normalizedNumber = normalizeNumber(number);
            boolean isRegex = normalizedNumber.indexOf('%') >= 0
                    || normalizedNumber.indexOf('_') >= 0;

            values.put(COLUMN_NORMALIZED, normalizedNumber);
            values.put(Blacklist.IS_REGEX, isRegex ? 1 : 0);
        }

        return values;
    }

    private void notifyChange() {
        getContext().getContentResolver().notifyChange(Blacklist.CONTENT_URI, null);
        mBackupManager.dataChanged();
    }

    // mostly a copy of PhoneNumberUtils.normalizeNumber,
    // with the exception of support for regex characters
    private static String normalizeNumber(String number) {
        int len = number.length();
        StringBuilder ret = new StringBuilder(len);

        for (int i = 0; i < len; i++) {
            char c = number.charAt(i);
            // Character.digit() supports ASCII and Unicode digits (fullwidth, Arabic-Indic, etc.)
            int digit = Character.digit(c, 10);
            if (digit != -1) {
                ret.append(digit);
            } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                return normalizeNumber(PhoneNumberUtils.convertKeypadLettersToDigits(number));
            } else if (i == 0 && c == '+') {
                ret.append(c);
            } else if (c == '*') {
                // replace regex match-multiple character by SQL equivalent
                ret.append('%');
            } else if (c == '.') {
                // replace regex-match-single character by SQL equivalent
                ret.append('_');
            }
        }

        return ret.toString();
    }
}

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
import android.text.TextUtils;
import android.util.Log;

public class BlacklistProvider extends ContentProvider {
    private static final String TAG = "BlacklistProvider";
    private static final boolean DEBUG = true;

    private static final String DATABASE_NAME = "blacklist.db";
    private static final String BLACKLIST_TABLE = "blacklist";
    private static final int DATABASE_VERSION = 1;

    private static final int BL_ALL         = 0;
    private static final int BL_ID          = 1;
    private static final int BL_NUMBER	    = 2;
    private static final int BL_PHONE       = 3;
    private static final int BL_MESSAGE     = 4;

    private static final UriMatcher
            sURIMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        sURIMatcher.addURI("blacklist", null,         BL_ALL);
        sURIMatcher.addURI("blacklist", "by-id/#",    BL_ID);
        sURIMatcher.addURI("blacklist", "#",          BL_NUMBER);
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
                    "number TEXT UNIQUE," +
                    "phone INTEGER DEFAULT 0," +
                    "message INTEGER DEFAULT 0);");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // won't happen, we're at version 1
        }
    }

    private DatabaseHelper mOpenHelper;

    @Override
    public boolean onCreate() {
        mOpenHelper = new DatabaseHelper(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
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
                String number = uri.getLastPathSegment();
                boolean regex = uri.getBooleanQueryParameter(Blacklist.REGEX_KEY, false);

                if (regex) {
                    // convert regex syntax to SQL like syntax
                    number = number.replace('*', '%').replace('.', '_');
                }
                qb.appendWhere(Blacklist.NUMBER + " " +
                        (regex ? "like" : "=") + " \"" + number + "\"");
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

        String number = initialValues.getAsString(Blacklist.NUMBER);
        if (TextUtils.isEmpty(number)) {
            Log.e(TAG, "Can't insert empty number");
            return null;
        }

        ContentValues values;
        if (initialValues != null) {
            values = new ContentValues(initialValues);
        } else {
            values = new ContentValues();
        }

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        long rowID = db.insert(BLACKLIST_TABLE, null, values);
        if (rowID <= 0) {
            return null;
        }

        if (DEBUG) Log.d(TAG, "inserted " + values + " rowID = " + rowID);
        notifyChange();

        return ContentUris.withAppendedId(Blacklist.CONTENT_BYID_URI, rowID);
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
                count = db.delete(BLACKLIST_TABLE, where, whereArgs);
                break;
            case BL_ID:
                count = db.delete(BLACKLIST_TABLE, Blacklist._ID + " = ?",
                        new String[] { uri.getLastPathSegment() });
            case BL_NUMBER:
                count = db.delete(BLACKLIST_TABLE, Blacklist.NUMBER + " = ?",
                        new String[] { uri.getLastPathSegment() });
                break;
            default:
                throw new UnsupportedOperationException("Cannot delete that URI: " + uri);
        }

        if (DEBUG) Log.d(TAG, "delete result count " + count);

        if (count > 0) {
            notifyChange();
        }

        return count;
    }

    @Override
    public int update(Uri uri, ContentValues values, String where, String[] whereArgs) {
        int count = 0;
        int match = sURIMatcher.match(uri);
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        if (DEBUG) {
            Log.v(TAG, "Delete uri=" + uri + ", match=" + match);
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
                    count = db.update(BLACKLIST_TABLE, values, Blacklist.NUMBER + " = ?",
                            new String[] { uri.getLastPathSegment() });
                    if (count == 0) {
                        // convenience: fall back to insert if number wasn't present
                        Uri insertUri = insert(uri, values);
                        if (insertUri != null) {
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

    private void notifyChange() {
        getContext().getContentResolver().notifyChange(Blacklist.CONTENT_URI, null);
    }
}

/*
** Copyright (c) 2013-2014, The Linux Foundation. All rights reserved.
** Not a Contribution.
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.android.providers.telephony;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriMatcher;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Environment;
import android.provider.Telephony;
import android.util.Config;
import android.util.Log;
import android.util.Xml;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

public class CdmaCallOptionProvider extends ContentProvider {

    private static final String DATABASE_NAME = "cdmacalloption.db";

    private static final int DATABASE_VERSION = 1;

    // call option type: call forwarding, cancel all, call waiting
    private static final int CFUNCONDITIONAL = 0;
    private static final int CFBUSY          = 1;
    private static final int CFNOREPLY       = 2;
    private static final int CFNOREACHABLE   = 3;
    private static final int CFDEACTIVATEALL = 4;
    private static final int CALLWAITING = 6;

    // call option state: activate, deactivate
    private static final int ACTIVATE = 1;
    private static final int DEACTIVATE = 2;

    private static final int URL_TELEPHONY = 1;
    private static final int URL_CFU = 2;
    private static final int URL_CFB = 3;
    private static final int URL_CFNRY = 4;
    private static final int URL_CFNRC = 5;
    private static final int URL_CFDA = 6;
    private static final int URL_CW = 7;

    private static final String TAG = "CdmaCallOptionProvider";
    private static final String CALL_OPTION_TABLE = "calloption";
    private static final String PARTNER_CDMA_CALL_PATH = "etc/cdma_call_conf.xml";
    private static final UriMatcher s_urlMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    private SQLiteOpenHelper mOpenHelper;

    static {
        s_urlMatcher.addURI("cdma", "calloption", URL_TELEPHONY);
        s_urlMatcher.addURI("cdma", "calloption/cfu", URL_CFU);
        s_urlMatcher.addURI("cdma", "calloption/cfb", URL_CFB);
        s_urlMatcher.addURI("cdma", "calloption/cfnry", URL_CFNRY);
        s_urlMatcher.addURI("cdma", "calloption/cfnrc", URL_CFNRC);
        s_urlMatcher.addURI("cdma", "calloption/cfda", URL_CFDA);
        s_urlMatcher.addURI("cdma", "calloption/cw", URL_CW);
    }

    private static class DatabaseHelper extends SQLiteOpenHelper {
        // Context to access resources with
        private Context mContext;

        /**
         * DatabaseHelper helper class for loading data profiles into a database.
         *
         * @param parser the system-default parser for apns.xml
         * @param confidential an optional parser for confidential APNS (stored separately)
         */
        public DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
            mContext = context;
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            // Set up the database schema
            db.execSQL("CREATE TABLE " + CALL_OPTION_TABLE +
                "(_id INTEGER PRIMARY KEY," +
                    "name TEXT," +
                    "numeric TEXT," +
                    "mcc TEXT," +
                    "mnc TEXT," +
                    "number TEXT," +
                    "type INTEGER," +
                    "category INTEGER," +
                    "state INTEGER);");

            initDatabase(db);
        }

        private void initDatabase(SQLiteDatabase db) {
            // Read cdma call option data (partner-provided)
            XmlPullParser confparser = null;
            // Environment.getRootDirectory() is a fancy way of saying ANDROID_ROOT or "/system".
            File confFile = new File(Environment.getRootDirectory(), PARTNER_CDMA_CALL_PATH);
            FileReader confreader = null;
            try {
                confreader = new FileReader(confFile);
                confparser = Xml.newPullParser();
                confparser.setInput(confreader);
                XmlUtils.beginDocument(confparser, "calloptions");
                loadProfiles(db, confparser);
            } catch (FileNotFoundException e) {
                // It's ok if the file isn't found. It means there isn't a confidential file
                // Log.e(TAG, "File not found: '" + confFile.getAbsolutePath() + "'");
            } catch (Exception e) {
                Log.e(TAG, "Exception while parsing '" + confFile.getAbsolutePath() + "'", e);
            } finally {
                try { if (confreader != null) confreader.close(); } catch (IOException e) { }
            }
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS " + CALL_OPTION_TABLE);
            onCreate(db);
        }

        /**
         * Gets the next row of data profile values.
         *
         * @param parser the parser
         * @return the row or null if it's not an call option
         */
        private ContentValues getRow(XmlPullParser parser) {
            // get the profile type from the XML file tags
            String prof_type = parser.getName();
            if (!"option".equals(prof_type)) {
                return null;
            }
            ContentValues map = new ContentValues();

            String mcc = parser.getAttributeValue(null, "mcc");
            String mnc = parser.getAttributeValue(null, "mnc");
            String numeric = mcc + mnc;

            map.put(Telephony.CdmaCallOptions.NUMERIC,numeric);
            map.put(Telephony.CdmaCallOptions.MCC, mcc);
            map.put(Telephony.CdmaCallOptions.MNC, mnc);
            map.put(Telephony.CdmaCallOptions.NAME, parser.getAttributeValue(null, "carrier"));
            map.put(Telephony.CdmaCallOptions.NUMBER, parser.getAttributeValue(null, "number"));

            // do not add NULL to the map so that insert() will set the default value
            String category = parser.getAttributeValue(null, "category");
            if (category != null) {
                map.put(Telephony.CdmaCallOptions.CATEGORY, Integer.parseInt(category));
            }
            String state = parser.getAttributeValue(null, "state");
            if (state != null) {
                map.put(Telephony.CdmaCallOptions.STATE, Integer.parseInt(state));
            }
            String type = parser.getAttributeValue(null, "type");
            if (type != null) {
                map.put(Telephony.CdmaCallOptions.TYPE, Integer.parseInt(type));
            }

            return map;
        }

        /*
         * Loads data profiles from xml file into the database
         *
         * @param db the sqlite database to write to
         * @param parser the xml parser
         *
         */
        private void loadProfiles(SQLiteDatabase db, XmlPullParser parser) {
            if (parser != null) {
                try {
                    while (true) {
                        XmlUtils.nextElement(parser);
                        ContentValues row = getRow(parser);
                        if (row != null) {
                            insertAddingDefaults(db, CALL_OPTION_TABLE, row);
                        } else {
                            break;  // do we really want to skip the rest of the file?
                        }
                    }
                } catch (XmlPullParserException e)  {
                    Log.e(TAG, "Got execption while getting perferred time zone.", e);
                } catch (IOException e) {
                    Log.e(TAG, "Got execption while getting perferred time zone.", e);
                }
            }
        }

        private void insertAddingDefaults(SQLiteDatabase db, String table, ContentValues row) {
            // Initialize defaults if any
            if (row.containsKey(Telephony.CdmaCallOptions.CATEGORY) == false) {
                row.put(Telephony.CdmaCallOptions.CATEGORY, -1);
            }
            if (row.containsKey(Telephony.CdmaCallOptions.STATE) == false) {
                row.put(Telephony.CdmaCallOptions.STATE, -1);
            }
            db.insert(CALL_OPTION_TABLE, null, row);
        }
    }

    @Override
    public boolean onCreate() {
        mOpenHelper = new DatabaseHelper(getContext());
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        return true;
    }



    @Override
    public Cursor query(Uri url, String[] projectionIn, String selection,
            String[] selectionArgs, String sort) {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables("calloption");

        int match = s_urlMatcher.match(url);
        switch (match) {
            // do nothing
            case URL_TELEPHONY: {
                break;
            }

            case URL_CFU: {
                qb.appendWhere("type = " + CFUNCONDITIONAL);
                break;
            }

            case URL_CFB: {
                qb.appendWhere("type = " + CFBUSY);
                break;
            }

            case URL_CFNRY: {
                qb.appendWhere("type = " + CFNOREPLY);
                break;
            }

            case URL_CFNRC: {
                qb.appendWhere("type = " + CFNOREACHABLE);
                break;
            }

            case URL_CFDA: {
                qb.appendWhere("type = " + CFDEACTIVATEALL);
                break;
            }

            case URL_CW: {
                qb.appendWhere("type = " + CALLWAITING);
                break;
            }

            default: {
                return null;
            }
        }

        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        Cursor ret = qb.query(db, projectionIn, selection, selectionArgs, null, null, sort);
        ret.setNotificationUri(getContext().getContentResolver(), url);
        return ret;
    }


    @Override
    public Uri insert(Uri url, ContentValues initialValues)
    {
        Uri result = null;

        //checkPermission();

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int match = s_urlMatcher.match(url);
        boolean notify = false;
        ContentValues values = new ContentValues(initialValues);
        switch (match)
        {
            case URL_TELEPHONY:
            {
                // TODO Review this. This code should probably not bet here.
                // It is valid for the database to return a null string.
                if (values.containsKey(Telephony.CdmaCallOptions.NAME) == false) {
                    values.put(Telephony.CdmaCallOptions.NAME, "");
                }
                if (values.containsKey(Telephony.CdmaCallOptions.NUMBER) == false) {
                    values.put(Telephony.CdmaCallOptions.NUMBER, "");
                }
                if (values.containsKey(Telephony.CdmaCallOptions.TYPE) == false) {
                    values.put(Telephony.CdmaCallOptions.TYPE, -1);
                }
                break;
            }
            case URL_CFU: {
                values.put(Telephony.CdmaCallOptions.TYPE, CFUNCONDITIONAL);
                break;
            }
            case URL_CFB: {
                values.put(Telephony.CdmaCallOptions.TYPE, CFBUSY);
                break;
            }
            case URL_CFNRY: {
                values.put(Telephony.CdmaCallOptions.TYPE, CFNOREPLY);
                break;
            }
            case URL_CFNRC: {
                values.put(Telephony.CdmaCallOptions.TYPE, CFNOREACHABLE);
                break;
            }
            case URL_CFDA: {
                values.put(Telephony.CdmaCallOptions.TYPE, CFDEACTIVATEALL);
                values.put(Telephony.CdmaCallOptions.STATE, DEACTIVATE);
                values.put(Telephony.CdmaCallOptions.CATEGORY, -1);
                break;
            }
            case URL_CW: {
                values.put(Telephony.CdmaCallOptions.TYPE, CALLWAITING);
                values.put(Telephony.CdmaCallOptions.CATEGORY, -1);
                break;
            }
            default: {
                throw new UnsupportedOperationException("Cannot delete that URL: " + url);
            }
        }
        long rowID = db.insert(CALL_OPTION_TABLE, null, values);
        if (rowID > 0) {
            result = ContentUris.withAppendedId(Telephony.CdmaCallOptions.CONTENT_URI, rowID);
            notify = true;
        }

        if (Config.LOGD) Log.d(TAG, "inserted " + values.toString() + " rowID = " + rowID);

        if (notify) {
            getContext().getContentResolver().notifyChange(
                    Telephony.CdmaCallOptions.CONTENT_URI, null);
        }

        return result;
    }

    @Override
    public int delete(Uri url, String where, String[] whereArgs)
    {
        int count;

        /**
         * we need to delete all the ssfc with the same mnc/mcc when a new card is inserted
         */
        //checkPermission();

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int match = s_urlMatcher.match(url);
        switch (match)
        {
            case URL_TELEPHONY:
            {
                count = db.delete(CALL_OPTION_TABLE, where, whereArgs);
                break;
            }

            default: {
                throw new UnsupportedOperationException("Cannot delete that URL: " + url);
            }
        }

        getContext().getContentResolver().notifyChange(
                Telephony.CdmaCallOptions.CONTENT_URI, null);

        return count;
    }

    @Override
    public int update(Uri url, ContentValues values, String where, String[] whereArgs)
    {
        int count = 0;

        checkPermission();

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int match = s_urlMatcher.match(url);
        switch (match)
        {
            case URL_TELEPHONY:
            {
                count = db.update(CALL_OPTION_TABLE, values, where, whereArgs);
                break;
            }
            default: {
                throw new UnsupportedOperationException("Cannot update that URL: " + url);
            }
        }

        if (count > 0) {
            getContext().getContentResolver().notifyChange(
                    Telephony.CdmaCallOptions.CONTENT_URI, null);
        }

        return count;
    }

    @Override
    public String getType(Uri url)
    {
        switch (s_urlMatcher.match(url)) {
        case URL_TELEPHONY:
            return "vnd.android.cursor.dir/telephony-calloption";

        default:
            throw new IllegalArgumentException("Unknown URL " + url);
        }
    }

    private void checkPermission() {
        // Check the permissions
        getContext().enforceCallingOrSelfPermission("android.permission.WRITE_CDMA_CALL_SETTINGS",
                "No permission to write Cdma call settings");
    }
}

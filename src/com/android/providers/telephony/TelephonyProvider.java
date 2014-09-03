/* //device/content/providers/telephony/TelephonyProvider.java
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
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Environment;
import android.provider.Telephony;
import android.telephony.TelephonyManager;
import android.telephony.MSimTelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Xml;

import com.android.internal.telephony.BaseCommands;
import com.android.internal.telephony.MSimConstants;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.util.XmlUtils;
import com.android.internal.telephony.MSimConstants;
import com.android.internal.telephony.TelephonyProperties;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;


public class TelephonyProvider extends ContentProvider
{
    private static final String DATABASE_NAME = "telephony.db";
    private static final boolean DBG = true;

    private static final int DATABASE_VERSION = 15 << 16;
    private static final int URL_TELEPHONY = 1;
    private static final int URL_CURRENT = 2;
    private static final int URL_ID = 3;
    private static final int URL_RESTOREAPN = 4;
    private static final int URL_PREFERAPN = 5;
    private static final int URL_PREFERAPN_NO_UPDATE = 6;
    private static final int URL_PREFERAPN_W_SUB_ID = 7;

    private static final String TAG = "TelephonyProvider";
    private static final String CARRIERS_TABLE = "carriers";

    private static final String PREF_FILE = "preferred-apn";
    private static final String COLUMN_APN_ID = "apn_id";

    private static final String PARTNER_APNS_PATH = "etc/apns-conf.xml";

    private static final String NUMERIC_MATCH_REGEX = "(numeric *= *[',\"][0-9]+[',\"])";
    private static final String NUMERIC_ADD_DEFAULT_REGEX = "\\($1 or numeric = '000000'\\)";
    private static final String NUMERIC_VMCCMNC_REGEX_PT1 = "\\($1 and \\(v_mccmnc " +
        "= '000000' or v_mccmnc = '";

    private static final String VISIT_AREA = "visit_area";

    private static boolean sConfigDefaultApnEnabled;
    private static boolean sConfigRoamingAreaApnRestrictionEnabled;

    private static final UriMatcher s_urlMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    private static final ContentValues s_currentNullMap;
    private static final ContentValues s_currentSetMap;

    static {
        s_urlMatcher.addURI("telephony", "carriers", URL_TELEPHONY);
        s_urlMatcher.addURI("telephony", "carriers/current", URL_CURRENT);
        s_urlMatcher.addURI("telephony", "carriers/#", URL_ID);
        s_urlMatcher.addURI("telephony", "carriers/restore", URL_RESTOREAPN);
        s_urlMatcher.addURI("telephony", "carriers/preferapn", URL_PREFERAPN);
        s_urlMatcher.addURI("telephony", "carriers/preferapn_no_update", URL_PREFERAPN_NO_UPDATE);
        s_urlMatcher.addURI("telephony", "carriers/preferapn/#", URL_PREFERAPN_W_SUB_ID);

        s_currentNullMap = new ContentValues(1);
        s_currentNullMap.put("current", (Long) null);

        s_currentSetMap = new ContentValues(1);
        s_currentSetMap.put("current", "1");
    }

    private static class DatabaseHelper extends SQLiteOpenHelper {
        // Context to access resources with
        private Context mContext;

        /**
         * DatabaseHelper helper class for loading apns into a database.
         *
         * @param context of the user.
         */
        public DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, getVersion(context));
            mContext = context;
        }

        private static int getVersion(Context context) {
            // Get the database version, combining a static schema version and the XML version
            Resources r = context.getResources();
            XmlResourceParser parser = r.getXml(com.android.internal.R.xml.apns);
            try {
                XmlUtils.beginDocument(parser, "apns");
                int publicversion = Integer.parseInt(parser.getAttributeValue(null, "version"));
                return DATABASE_VERSION | publicversion;
            } catch (Exception e) {
                Log.e(TAG, "Can't get version of APN database", e);
                return DATABASE_VERSION;
            } finally {
                parser.close();
            }
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            // Set up the database schema
            db.execSQL("CREATE TABLE " + CARRIERS_TABLE +
                "(_id INTEGER PRIMARY KEY," +
                    "name TEXT," +
                    "numeric TEXT," +
                    "mcc TEXT," +
                    "mnc TEXT," +
                    "apn TEXT," +
                    "user TEXT," +
                    "server TEXT," +
                    "password TEXT," +
                    "proxy TEXT," +
                    "port TEXT," +
                    "mmsproxy TEXT," +
                    "mmsport TEXT," +
                    "mmsc TEXT," +
                    "authtype INTEGER," +
                    "type TEXT," +
                    "current INTEGER," +
                    "protocol TEXT," +
                    "roaming_protocol TEXT," +
                    "carrier_enabled BOOLEAN," +
                    "bearer INTEGER," +
                    "mvno_type TEXT," +
                    "mvno_match_data TEXT," +
                    "preferred BOOLEAN DEFAULT 0," +
                    "read_only BOOLEAN DEFAULT 0," +
                    "ppp_number TEXT," +
                    "localized_name TEXT," +
                    "visit_area TEXT," +
                    "v_mccmnc TEXT);");

            initDatabase(db);
        }

        private int getDefaultPreferredApnId(SQLiteDatabase db) {
            int id = -1;
            String configPref = mContext.getResources().getString(R.string.config_preferred_apn, "");
            if (!TextUtils.isEmpty(configPref)) {
                String[] s = configPref.split(",");
                if (s.length == 3) {
                    Cursor c = db.query("carriers", new String[] { "_id" },
                            "apn='" + s[0] + "' AND mcc='" + s[1] + "' AND mnc='" + s[2] + "'",
                            null, null, null, null);
                    if (c.moveToFirst()) {
                        id = c.getInt(0);
                    }
                    c.close();
                }
            }
            return id;
        }

        private void initDatabase(SQLiteDatabase db) {
            // Read internal APNS data
            Resources r = mContext.getResources();
            XmlResourceParser parser = r.getXml(com.android.internal.R.xml.apns);
            int publicversion = -1;
            try {
                XmlUtils.beginDocument(parser, "apns");
                publicversion = Integer.parseInt(parser.getAttributeValue(null, "version"));
                loadApns(db, parser);
            } catch (Exception e) {
                Log.e(TAG, "Got exception while loading APN database.", e);
            } finally {
                parser.close();
            }

            // Read external APNS data (partner-provided)
            XmlPullParser confparser = null;
            // Environment.getRootDirectory() is a fancy way of saying ANDROID_ROOT or "/system".
            File confFile = new File(Environment.getRootDirectory(), PARTNER_APNS_PATH);
            FileReader confreader = null;
            try {
                confreader = new FileReader(confFile);
                confparser = Xml.newPullParser();
                confparser.setInput(confreader);
                XmlUtils.beginDocument(confparser, "apns");

                // Sanity check. Force internal version and confidential versions to agree
                int confversion = Integer.parseInt(confparser.getAttributeValue(null, "version"));
                if (publicversion != confversion) {
                    throw new IllegalStateException("Internal APNS file version doesn't match "
                            + confFile.getAbsolutePath());
                }

                loadApns(db, confparser);
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
            if (oldVersion < (5 << 16 | 6)) {
                // 5 << 16 is the Database version and 6 in the xml version.

                // This change adds a new authtype column to the database.
                // The auth type column can have 4 values: 0 (None), 1 (PAP), 2 (CHAP)
                // 3 (PAP or CHAP). To avoid breaking compatibility, with already working
                // APNs, the unset value (-1) will be used. If the value is -1.
                // the authentication will default to 0 (if no user / password) is specified
                // or to 3. Currently, there have been no reported problems with
                // pre-configured APNs and hence it is set to -1 for them. Similarly,
                // if the user, has added a new APN, we set the authentication type
                // to -1.

                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN authtype INTEGER DEFAULT -1;");

                oldVersion = 5 << 16 | 6;
            }
            if (oldVersion < (6 << 16 | 6)) {
                // Add protcol fields to the APN. The XML file does not change.
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN protocol TEXT DEFAULT " +
                        mContext.getString(R.string.default_protocol) + ";");
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN roaming_protocol TEXT DEFAULT " +
                        mContext.getString(R.string.default_protocol) + ";");
                oldVersion = 6 << 16 | 6;
            }
            if (oldVersion < (7 << 16 | 6)) {
                // Add carrier_enabled, bearer fields to the APN. The XML file does not change.
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN carrier_enabled BOOLEAN DEFAULT 1;");
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN bearer INTEGER DEFAULT 0;");
                oldVersion = 7 << 16 | 6;
            }
            if (oldVersion < (8 << 16 | 6)) {
                // Add mvno_type, mvno_match_data fields to the APN.
                // The XML file does not change.
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN mvno_type TEXT DEFAULT '';");
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN mvno_match_data TEXT DEFAULT '';");
                oldVersion = 8 << 16 | 6;
            }
            if (oldVersion < (9 << 16 | 6)) {
                // Dummy upgrade from previous CM versions
                oldVersion = 9 << 16 | 6;
            }
            if (oldVersion < (10 << 16 | 6)) {
                // Add preferred field to the APN.
                // The XML file does not change.
                try {
                    db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                            " ADD COLUMN preferred BOOLEAN DEFAULT 0;");
                    oldVersion = 10 << 16 | 6;
                } catch (SQLException e) {
                    // Original implementation for preferred apn feature
                    // didn't include new version for database
                    // Consequently we can have version 8 database with and
                    // without preferred column
                    // Hence, this operation can result in exception
                    // (if column is already there)
                    // Just log it
                    Log.e(TAG, "Exception adding preferred column to database. ", e);
                }
            }
            if (oldVersion < (11 << 16 | 6)) {
                try {
                    db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                            " ADD COLUMN read_only BOOLEAN DEFAULT 0;");
                    oldVersion = 11 << 16 | 6;
                } catch (SQLException e) {
                    Log.e(TAG, "Exception adding read_only column to database. ", e);
                }
            }
            if (oldVersion < (12 << 16 | 6)) {
                try {
                    db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                            " ADD COLUMN ppp_number TEXT DEFAULT '';");
                    oldVersion = 12 << 16 | 6;
                } catch (SQLException e) {
                    Log.e(TAG, "Exception adding ppp_number column to database. ", e);
                }
            }
            if (oldVersion < (13 << 16 | 6)) {
                try {
                    db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                            " ADD COLUMN localized_name TEXT DEFAULT '';");
                    oldVersion = 13 << 16 | 6;
                } catch (SQLException e) {
                    Log.e(TAG, "Exception adding localized_name column to database. ", e);
                }
            }
            if (oldVersion < (14 << 16 | 6)) {
                try {
                    db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                            " ADD COLUMN visit_area TEXT DEFAULT '';");
                    oldVersion = 14 << 16 | 6;
                } catch (SQLException e) {
                    Log.e(TAG, "Exception adding visit_area column to database. ", e);
                }
            }
            if (oldVersion < (15 << 16 | 6)) {
                try {
                    db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                            " ADD COLUMN v_mccmnc TEXT DEFAULT '';");
                    oldVersion = 15 << 16 | 6;
                } catch (SQLException e) {
                    Log.e(TAG, "Exception adding v_mccmnc column to database. ", e);
                }
            }
        }

        /**
         * Gets the next row of apn values.
         *
         * @param parser the parser
         * @return the row or null if it's not an apn
         */
        private ContentValues getRow(XmlPullParser parser) {
            if (!"apn".equals(parser.getName())) {
                return null;
            }

            ContentValues map = new ContentValues();

            String mcc = parser.getAttributeValue(null, "mcc");
            String mnc = parser.getAttributeValue(null, "mnc");
            String numeric = mcc + mnc;

            map.put(Telephony.Carriers.NUMERIC,numeric);
            map.put(Telephony.Carriers.MCC, mcc);
            map.put(Telephony.Carriers.MNC, mnc);
            map.put(Telephony.Carriers.NAME, parser.getAttributeValue(null, "carrier"));
            map.put(Telephony.Carriers.APN, parser.getAttributeValue(null, "apn"));
            map.put(Telephony.Carriers.USER, parser.getAttributeValue(null, "user"));
            map.put(Telephony.Carriers.SERVER, parser.getAttributeValue(null, "server"));
            map.put(Telephony.Carriers.PASSWORD, parser.getAttributeValue(null, "password"));
            map.put(mContext.getString(R.string.ppp_number),
                             parser.getAttributeValue(null, "ppp_number"));
            map.put(mContext.getString(R.string.localized_name),
                    parser.getAttributeValue(null, "localized_name"));
            map.put(VISIT_AREA, parser.getAttributeValue(null, "visit_area"));

            // do not add NULL to the map so that insert() will set the default value
            String proxy = parser.getAttributeValue(null, "proxy");
            if (proxy != null) {
                map.put(Telephony.Carriers.PROXY, proxy);
            }
            String port = parser.getAttributeValue(null, "port");
            if (port != null) {
                map.put(Telephony.Carriers.PORT, port);
            }
            String mmsproxy = parser.getAttributeValue(null, "mmsproxy");
            if (mmsproxy != null) {
                map.put(Telephony.Carriers.MMSPROXY, mmsproxy);
            }
            String mmsport = parser.getAttributeValue(null, "mmsport");
            if (mmsport != null) {
                map.put(Telephony.Carriers.MMSPORT, mmsport);
            }
            map.put(Telephony.Carriers.MMSC, parser.getAttributeValue(null, "mmsc"));
            String type = parser.getAttributeValue(null, "type");
            if (type != null) {
                map.put(Telephony.Carriers.TYPE, type);
            }

            String auth = parser.getAttributeValue(null, "authtype");
            if (auth != null) {
                map.put(Telephony.Carriers.AUTH_TYPE, Integer.parseInt(auth));
            }

            String protocol = parser.getAttributeValue(null, "protocol");
            if (protocol != null) {
                map.put(Telephony.Carriers.PROTOCOL, protocol);
            }

            String roamingProtocol = parser.getAttributeValue(null, "roaming_protocol");
            if (roamingProtocol != null) {
                map.put(Telephony.Carriers.ROAMING_PROTOCOL, roamingProtocol);
            }

            String carrierEnabled = parser.getAttributeValue(null, "carrier_enabled");
            if (carrierEnabled != null) {
                map.put(Telephony.Carriers.CARRIER_ENABLED, Boolean.parseBoolean(carrierEnabled));
            }

            String bearer = parser.getAttributeValue(null, "bearer");
            if (bearer != null) {
                map.put(Telephony.Carriers.BEARER, Integer.parseInt(bearer));
            }

            String mvno_type = parser.getAttributeValue(null, "mvno_type");
            if (mvno_type != null) {
                String mvno_match_data = parser.getAttributeValue(null, "mvno_match_data");
                if (mvno_match_data != null) {
                    map.put(Telephony.Carriers.MVNO_TYPE, mvno_type);
                    map.put(Telephony.Carriers.MVNO_MATCH_DATA, mvno_match_data);
                }
            }

            String preferred = parser.getAttributeValue(null, "preferred");
            if (preferred != null) {
                map.put(Telephony.Carriers.PREFERRED,  Boolean.parseBoolean(preferred));
            }

            String readOnly = parser.getAttributeValue(null, "read_only");
            if (readOnly != null) {
                map.put(mContext.getString(R.string.read_only), Boolean.parseBoolean(readOnly));
            }

            String v_mccmnc = parser.getAttributeValue(null, "v_mccmnc");
            if (v_mccmnc != null) {
                map.put(mContext.getString(R.string.v_mccmnc), v_mccmnc);
            }
            return map;
        }

        /*
         * Loads apns from xml file into the database
         *
         * @param db the sqlite database to write to
         * @param parser the xml parser
         *
         */
        private void loadApns(SQLiteDatabase db, XmlPullParser parser) {
            if (parser != null) {
                try {
                    db.beginTransaction();
                    XmlUtils.nextElement(parser);
                    while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                        ContentValues row = getRow(parser);
                        if (row == null) {
                            throw new XmlPullParserException("Expected 'apn' tag", parser, null);
                        }
                        insertAddingDefaults(db, CARRIERS_TABLE, row);
                        XmlUtils.nextElement(parser);
                    }
                    db.setTransactionSuccessful();
                } catch (XmlPullParserException e) {
                    Log.e(TAG, "Got XmlPullParserException while loading apns.", e);
                } catch (IOException e) {
                    Log.e(TAG, "Got IOException while loading apns.", e);
                } catch (SQLException e) {
                    Log.e(TAG, "Got SQLException while loading apns.", e);
                } finally {
                    db.endTransaction();
                }
            }
        }

        private void insertAddingDefaults(SQLiteDatabase db, String table, ContentValues row) {
            // Initialize defaults if any
            if (row.containsKey(Telephony.Carriers.AUTH_TYPE) == false) {
                row.put(Telephony.Carriers.AUTH_TYPE, -1);
            }
            if (row.containsKey(Telephony.Carriers.PROTOCOL) == false) {
                row.put(Telephony.Carriers.PROTOCOL, mContext.getString(R.string.default_protocol));
            }
            if (row.containsKey(Telephony.Carriers.ROAMING_PROTOCOL) == false) {
                row.put(Telephony.Carriers.ROAMING_PROTOCOL,
                        mContext.getString(R.string.default_protocol));
            }
            if (row.containsKey(Telephony.Carriers.CARRIER_ENABLED) == false) {
                row.put(Telephony.Carriers.CARRIER_ENABLED, true);
            }
            if (row.containsKey(Telephony.Carriers.BEARER) == false) {
                row.put(Telephony.Carriers.BEARER, 0);
            }
            if (row.containsKey(Telephony.Carriers.MVNO_TYPE) == false) {
                row.put(Telephony.Carriers.MVNO_TYPE, "");
            }
            if (row.containsKey(Telephony.Carriers.MVNO_MATCH_DATA) == false) {
                row.put(Telephony.Carriers.MVNO_MATCH_DATA, "");
            }

            if (row.containsKey(Telephony.Carriers.PREFERRED) == false) {
                row.put(Telephony.Carriers.PREFERRED, false);
            }

            if (row.containsKey(mContext.getString(R.string.read_only)) == false) {
                row.put(mContext.getString(R.string.read_only), false);
            }

            if (row.containsKey(mContext.getString(R.string.v_mccmnc)) == false) {
                row.put(mContext.getString(R.string.v_mccmnc), "000000");
            }
            db.insert(CARRIERS_TABLE, null, row);
        }
    }

    @Override
    public boolean onCreate() {
        mOpenHelper = new DatabaseHelper(getContext());
        sConfigDefaultApnEnabled = getContext().getResources()
                .getBoolean(R.bool.config_enable_default_apn);
        sConfigRoamingAreaApnRestrictionEnabled = getContext().getResources()
                .getBoolean(R.bool.config_enable_roaming_area_apn_restriction);
        return true;
    }

    private String getColumnApnIdKey(int subId) {
        String result = COLUMN_APN_ID;
        // In case multi-sim is enabled,
        // if subId is given, use column name "apn_id" + sub id;
        // if subId is not given, use column name "apn_id" + preferred data sub id.
        //
        // In case multi-sim is not enabled,
        // use column name "apn_id".
        if (MSimTelephonyManager.getDefault().isMultiSimEnabled()) {
            switch (subId) {
            case MSimConstants.SUB1:
            case MSimConstants.SUB2:
                result += String.valueOf(subId);
                break;
            default:
                result += String.valueOf(MSimTelephonyManager.getDefault()
                        .getPreferredDataSubscription());
                break;
           }
        }
        Log.d(TAG, "Column apn id key is '" + result + "'");
        return result;
    }

    private void setPreferredApnId(Long id, int subId) {
        SharedPreferences sp = getContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.putLong(getColumnApnIdKey(subId), id != null ? id.longValue() : -1);
        editor.apply();
    }

    private void setPreferredApnId(Long id) {
        setPreferredApnId(id, -1);
    }

    private String getOperatorNumeric(int subId) {
        if (subId != MSimConstants.SUB1 && subId != MSimConstants.SUB2) {
            subId = MSimTelephonyManager.getDefault().getDefaultSubscription();
        }
        String numeric = MSimTelephonyManager.getTelephonyProperty(
                TelephonyProperties.PROPERTY_APN_SIM_OPERATOR_NUMERIC, subId, null);
        if (numeric != null && numeric.length() > 0) {
            return numeric;
        } else {
            return null;
        }
    }

    private long getPreferredApnId(int subId) {
        long apnId;
        SharedPreferences sp = getContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        apnId = sp.getLong(getColumnApnIdKey(subId), -1);
        if (apnId == -1) {
            // Check if there is an initial preferred apn
            String numeric = getOperatorNumeric(subId);
            if (numeric != null) {
                checkPermission();
                try {
                    SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
                    qb.setTables("carriers");

                    String where;
                    where = "numeric=\"" + numeric + "\"";
                    where += " AND preferred = 1";

                    SQLiteDatabase db = mOpenHelper.getReadableDatabase();
                    Cursor cursor = qb.query(db, new String[] {"_id"}, where,
                            null, null, null, Telephony.Carriers.DEFAULT_SORT_ORDER);
                    cursor.moveToFirst();
                    if (!cursor.isAfterLast()) {
                        final int ID_INDEX = 0;
                        String key = cursor.getString(ID_INDEX);
                        apnId = Long.valueOf(key);
                        Log.d(TAG, "Found an inital preferred apn. id = " + apnId);
                    } else {
                        apnId = getDefaultPreferredApnId();
                        if (apnId > -1) {
                                setPreferredApnId(apnId);
                        }
                    }
                } catch (SQLException e) {
                    Log.e(TAG, "got exception while checking initial preferred apn: " + e);
                }
            }
        }

        return apnId;
    }

    private long getPreferredApnId() {
        return getPreferredApnId(-1);
    }

    private long getDefaultPreferredApnId() {
        long id = -1;
        String configPref = getContext().getResources().getString(R.string.config_preferred_apn, "");
        if (!TextUtils.isEmpty(configPref)) {
            String[] s = configPref.split(",");
            if (s.length == 3) {
                Cursor c = mOpenHelper.getReadableDatabase().query("carriers", new String[] { "_id" },
                        "apn='" + s[0] + "' AND mcc='" + s[1] + "' AND mnc='" + s[2] + "'",
                        null, null, null, null);
                if (c.moveToFirst()) {
                    id = c.getLong(0);
                }
                c.close();
            }
        }
        Log.d(TAG, "Preferred APN: " + id);
        return id;
    }

    private int parseSubId(Uri url) {
        int subId = -1;
        try {
            subId = Integer.parseInt(url.getLastPathSegment());
        } catch (NumberFormatException e) {
            Log.e(TAG, "NumberFormatException: ", e);
        }
        Log.d(TAG, "SUB ID in the uri is" + subId);
        return subId;
    }

    @Override
    public Cursor query(Uri url, String[] projectionIn, String selection,
            String[] selectionArgs, String sort) {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setStrict(true); // a little protection from injection attacks
        qb.setTables("carriers");

        int match = s_urlMatcher.match(url);
        switch (match) {
            // do nothing
            case URL_TELEPHONY: {
                break;
            }


            case URL_CURRENT: {
                qb.appendWhere("current IS NOT NULL");
                // do not ignore the selection since MMS may use it.
                //selection = null;
                break;
            }

            case URL_ID: {
                qb.appendWhere("_id = " + url.getPathSegments().get(1));
                break;
            }

            case URL_PREFERAPN:
            case URL_PREFERAPN_NO_UPDATE: {
                qb.appendWhere("_id = " + getPreferredApnId());
                break;
            }

            case URL_PREFERAPN_W_SUB_ID: {
                qb.appendWhere("_id = " + getPreferredApnId(parseSubId(url)));
                break;
            }

            default: {
                return null;
            }
        }

        if (projectionIn != null) {
            for (String column : projectionIn) {
                if (Telephony.Carriers.TYPE.equals(column) ||
                        Telephony.Carriers.MMSC.equals(column) ||
                        Telephony.Carriers.MMSPROXY.equals(column) ||
                        Telephony.Carriers.MMSPORT.equals(column) ||
                        Telephony.Carriers.APN.equals(column)) {
                    // noop
                } else {
                    checkPermission();
                    break;
                }
            }
        } else {
            // null returns all columns, so need permission check
            checkPermission();
        }

        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        Cursor ret = null;
        try {
            if (sConfigRoamingAreaApnRestrictionEnabled) {
                // Replaces WHERE clause from
                //  numeric = 'xxxxxx'
                // to
                //  (numeric = 'xxxxxx' and (v_mccmnc = '000000' or v_mccmnc = 'yyyyyy'))
                String newSelection = null;
                if (selection != null) {
                    String operatorNumeric = null;
                    if (MSimTelephonyManager.getDefault().isMultiSimEnabled()) {
                        operatorNumeric = MSimTelephonyManager.getDefault().getNetworkOperator(
                                MSimTelephonyManager.getDefault().getPreferredDataSubscription());
                    } else {
                        operatorNumeric = TelephonyManager.getDefault().getNetworkOperator();
                    }

                    operatorNumeric = (operatorNumeric == null) ? "" : operatorNumeric;
                    String replacement = NUMERIC_VMCCMNC_REGEX_PT1 + operatorNumeric
                        + "'\\)\\)";
                    newSelection = selection.replaceAll(NUMERIC_MATCH_REGEX,
                            replacement);
                    Log.d(TAG, "Selection has been replaced to: " + newSelection);
                }
                ret = qb.query(db, projectionIn, newSelection, selectionArgs, null, null, sort);
            } else {
                ret = qb.query(db, projectionIn, selection, selectionArgs, null, null, sort);
            }

            if (sConfigDefaultApnEnabled) {
                // Additional query for default APNs.
                if (ret != null && ret.getCount() == 0) {
                    String newSelection = null;
                    if (selection != null) {
                        newSelection = selection.replaceAll(NUMERIC_MATCH_REGEX,
                                NUMERIC_ADD_DEFAULT_REGEX);
                    }
                    ret = qb.query(db, projectionIn, newSelection, selectionArgs, null, null, sort);
                }
            }
        } catch (SQLException e) {
            Log.e(TAG, "got exception when querying: " + e);
        }
        if (ret != null)
            ret.setNotificationUri(getContext().getContentResolver(), url);
        return ret;
    }

    @Override
    public String getType(Uri url)
    {
        switch (s_urlMatcher.match(url)) {
        case URL_TELEPHONY:
            return "vnd.android.cursor.dir/telephony-carrier";

        case URL_ID:
            return "vnd.android.cursor.item/telephony-carrier";

        case URL_PREFERAPN:
        case URL_PREFERAPN_NO_UPDATE:
        case URL_PREFERAPN_W_SUB_ID:
            return "vnd.android.cursor.item/telephony-carrier";

        default:
            throw new IllegalArgumentException("Unknown URL " + url);
        }
    }

    @Override
    public Uri insert(Uri url, ContentValues initialValues)
    {
        Uri result = null;

        checkPermission();

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int match = s_urlMatcher.match(url);
        boolean notify = false;
        switch (match)
        {
            case URL_TELEPHONY:
            {
                ContentValues values;
                if (initialValues != null) {
                    values = new ContentValues(initialValues);
                } else {
                    values = new ContentValues();
                }

                // TODO Review this. This code should probably not bet here.
                // It is valid for the database to return a null string.
                if (!values.containsKey(Telephony.Carriers.NAME)) {
                    values.put(Telephony.Carriers.NAME, "");
                }
                if (!values.containsKey(Telephony.Carriers.APN)) {
                    values.put(Telephony.Carriers.APN, "");
                }
                if (!values.containsKey(Telephony.Carriers.PORT)) {
                    values.put(Telephony.Carriers.PORT, "");
                }
                if (!values.containsKey(Telephony.Carriers.PROXY)) {
                    values.put(Telephony.Carriers.PROXY, "");
                }
                if (!values.containsKey(Telephony.Carriers.USER)) {
                    values.put(Telephony.Carriers.USER, "");
                }
                if (!values.containsKey(Telephony.Carriers.SERVER)) {
                    values.put(Telephony.Carriers.SERVER, "");
                }
                if (!values.containsKey(Telephony.Carriers.PASSWORD)) {
                    values.put(Telephony.Carriers.PASSWORD, "");
                }
                if (!values.containsKey(Telephony.Carriers.MMSPORT)) {
                    values.put(Telephony.Carriers.MMSPORT, "");
                }
                if (!values.containsKey(Telephony.Carriers.MMSPROXY)) {
                    values.put(Telephony.Carriers.MMSPROXY, "");
                }
                if (!values.containsKey(Telephony.Carriers.AUTH_TYPE)) {
                    values.put(Telephony.Carriers.AUTH_TYPE, -1);
                }
                if (!values.containsKey(Telephony.Carriers.PROTOCOL)) {
                    values.put(Telephony.Carriers.PROTOCOL,
                            getContext().getString(R.string.default_protocol));
                }
                if (!values.containsKey(Telephony.Carriers.ROAMING_PROTOCOL)) {
                    values.put(Telephony.Carriers.ROAMING_PROTOCOL,
                            getContext().getString(R.string.default_protocol));
                }
                if (!values.containsKey(Telephony.Carriers.CARRIER_ENABLED)) {
                    values.put(Telephony.Carriers.CARRIER_ENABLED, true);
                }
                if (!values.containsKey(Telephony.Carriers.BEARER)) {
                    values.put(Telephony.Carriers.BEARER, 0);
                }
                if (!values.containsKey(Telephony.Carriers.MVNO_TYPE)) {
                    values.put(Telephony.Carriers.MVNO_TYPE, "");
                }
                if (!values.containsKey(Telephony.Carriers.MVNO_MATCH_DATA)) {
                    values.put(Telephony.Carriers.MVNO_MATCH_DATA, "");
                }
                if (!values.containsKey(Telephony.Carriers.PREFERRED)) {
                    values.put(Telephony.Carriers.PREFERRED, false);
                }

                if (!values.containsKey(getContext().getString(R.string.read_only))) {
                    values.put(getContext().getString(R.string.read_only), false);
                }
                if (!values.containsKey(getContext().getString(R.string.localized_name))) {
                    values.put(getContext().getString(R.string.localized_name), "");
                }
                if (!values.containsKey(getContext().getString(R.string.v_mccmnc))) {
                    values.put(getContext().getString(R.string.v_mccmnc), "000000");
                }

                long rowID = db.insert(CARRIERS_TABLE, null, values);
                if (rowID > 0)
                {
                    result = ContentUris.withAppendedId(Telephony.Carriers.CONTENT_URI, rowID);
                    notify = true;
                }

                if (false) Log.d(TAG, "inserted " + values.toString() + " rowID = " + rowID);
                break;
            }

            case URL_CURRENT:
            {
                // null out the previous operator
                db.update("carriers", s_currentNullMap, "current IS NOT NULL", null);

                String numeric = initialValues.getAsString("numeric");
                int updated = db.update("carriers", s_currentSetMap,
                        "numeric = '" + numeric + "'", null);

                if (updated > 0)
                {
                    if (false) {
                        Log.d(TAG, "Setting numeric '" + numeric + "' to be the current operator");
                    }
                }
                else
                {
                    Log.e(TAG, "Failed setting numeric '" + numeric + "' to the current operator");
                }
                break;
            }

            case URL_PREFERAPN:
            case URL_PREFERAPN_NO_UPDATE:
            {
                if (initialValues != null) {
                    if(initialValues.containsKey(COLUMN_APN_ID)) {
                        setPreferredApnId(initialValues.getAsLong(COLUMN_APN_ID));
                    }
                }
                break;
            }

            case URL_PREFERAPN_W_SUB_ID:
            {
                if (initialValues != null) {
                    if(initialValues.containsKey(COLUMN_APN_ID)) {
                        setPreferredApnId(initialValues.getAsLong(COLUMN_APN_ID), parseSubId(url));
                    }
                }
                break;
            }
        }

        if (notify) {
            getContext().getContentResolver().notifyChange(Telephony.Carriers.CONTENT_URI, null);
        }

        return result;
    }

    @Override
    public int delete(Uri url, String where, String[] whereArgs)
    {
        int count = 0;

        checkPermission();

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int match = s_urlMatcher.match(url);
        switch (match)
        {
            case URL_TELEPHONY:
            {
                count = db.delete(CARRIERS_TABLE, where, whereArgs);
                break;
            }

            case URL_CURRENT:
            {
                count = db.delete(CARRIERS_TABLE, where, whereArgs);
                break;
            }

            case URL_ID:
            {
                count = db.delete(CARRIERS_TABLE, Telephony.Carriers._ID + "=?",
                        new String[] { url.getLastPathSegment() });
                break;
            }

            case URL_RESTOREAPN: {
                count = 1;
                restoreDefaultAPN();
                break;
            }

            case URL_PREFERAPN:
            case URL_PREFERAPN_NO_UPDATE:
            {
                setPreferredApnId((long)-1);
                if (match == URL_PREFERAPN) count = 1;
                break;
            }

            case URL_PREFERAPN_W_SUB_ID:
            {
                setPreferredApnId((long)-1, parseSubId(url));
                count = 1;
                break;
            }

            default: {
                throw new UnsupportedOperationException("Cannot delete that URL: " + url);
            }
        }

        if (count > 0) {
            getContext().getContentResolver().notifyChange(Telephony.Carriers.CONTENT_URI, null);
        }

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
                count = db.update(CARRIERS_TABLE, values, where, whereArgs);
                break;
            }

            case URL_CURRENT:
            {
                count = db.update(CARRIERS_TABLE, values, where, whereArgs);
                break;
            }

            case URL_ID:
            {
                if (where != null || whereArgs != null) {
                    throw new UnsupportedOperationException(
                            "Cannot update URL " + url + " with a where clause");
                }
                count = db.update(CARRIERS_TABLE, values, Telephony.Carriers._ID + "=?",
                        new String[] { url.getLastPathSegment() });
                break;
            }

            case URL_PREFERAPN:
            case URL_PREFERAPN_NO_UPDATE:
            {
                if (values != null) {
                    if (values.containsKey(COLUMN_APN_ID)) {
                        setPreferredApnId(values.getAsLong(COLUMN_APN_ID));
                        if (match == URL_PREFERAPN) count = 1;
                    }
                }
                break;
            }

            case URL_PREFERAPN_W_SUB_ID:
            {
                if (values != null) {
                    if (values.containsKey(COLUMN_APN_ID)) {
                        setPreferredApnId(values.getAsLong(COLUMN_APN_ID), parseSubId(url));
                        count = 1;
                    }
                }
                break;
            }

            default: {
                throw new UnsupportedOperationException("Cannot update that URL: " + url);
            }
        }

        if (count > 0) {
            getContext().getContentResolver().notifyChange(Telephony.Carriers.CONTENT_URI, null);
        }

        return count;
    }

    private void checkPermission() {
        getContext().enforceCallingOrSelfPermission("android.permission.WRITE_APN_SETTINGS",
                "No permission to write APN settings");
    }

    private DatabaseHelper mOpenHelper;

    private void restoreDefaultAPN() {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        try {
            db.delete(CARRIERS_TABLE, null, null);
        } catch (SQLException e) {
            Log.e(TAG, "got exception when deleting to restore: " + e);
        }
        setPreferredApnId((long)-1);
        mOpenHelper.initDatabase(db);
        setPreferredApnId(getDefaultPreferredApnId());
    }
}

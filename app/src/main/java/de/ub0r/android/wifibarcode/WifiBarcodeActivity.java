/*
 * Copyright (C) 2011-2012 Felix Bechstein
 *
 * This file is part of WifiBarcode.
 *
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; If not, see <http://www.gnu.org/licenses/>.
 */
package de.ub0r.android.wifibarcode;

import android.app.AlertDialog.Builder;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.actionbarsherlock.app.SherlockActivity;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;

import de.ub0r.android.logg0r.Log;

/**
 * Main {@link SherlockActivity} showing wifi configuration and barcodes.
 *
 * @author flx
 */
public final class WifiBarcodeActivity extends SherlockActivity implements
        OnClickListener {

    /**
     * Tag for log output.
     */
    private static final String TAG = "WifiBarcodeActivity";

    private static final String BARCODE_READER_URL = "https://play.google.com/store/apps/details?id=com.google.zxing.client.android";

    private static final String SECRETS_FILE_PLAIN = "wpa_supplicant.conf";
    private static final String SECRETS_FILE_XML = "WifiConfigStore.xml";
    private static final String[] SECRET_FILES = new String[]{SECRETS_FILE_XML, SECRETS_FILE_PLAIN};

    /**
     * Extra: barcode's bitmap.
     */
    static final String EXTRA_BARCODE = "barcode";

    /**
     * Extra: barcode's title.
     */
    static final String EXTRA_TITLE = "title";

    /**
     * Local {@link Spinner}s.
     */
    private Spinner mSpConfigs, mSpNetType;

    /**
     * Local {@link EditText}s.
     */
    private EditText mEtSsid, mEtPassword;
    private Bitmap mCurrentBarcode;

    /**
     * BarCode's size.
     */
    private int barcodeSize = 200;

    /**
     * Extra: Got root?
     */
    private static final String EXTRA_GOT_ROOT = "got_root";

    /**
     * False if runAsRoot failed.
     */
    private boolean mGotRoot = true;

    private boolean mFirstLoad = true;

    private boolean mXmlConfig = false;

    /**
     * Show wifi configuration as {@link ArrayAdapter}.
     */
    private static class WifiAdapter extends ArrayAdapter<WifiConfiguration> {

        /**
         * Passwords.
         */
        private final HashMap<WifiConfiguration, String> passwords = new HashMap<>();

        /**
         * Default constructor.
         *
         * @param context            {@link Context}
         * @param textViewResourceId Resource for item views.
         */
        public WifiAdapter(final Context context, final int textViewResourceId) {
            super(context, textViewResourceId);
        }

        @NonNull
        @Override
        public View getView(final int position, final View convertView, @NonNull final ViewGroup parent) {
            View v = super.getView(position, convertView, parent);
            ((TextView) v.findViewById(android.R.id.text1)).setText(this
                    .getItem(position).SSID.replaceAll("\"", ""));
            return v;
        }

        @Override
        public View getDropDownView(final int position, final View convertView,
                                    @NonNull final ViewGroup parent) {
            View v = super.getDropDownView(position, convertView, parent);
            assert v != null;
            ((TextView) v.findViewById(android.R.id.text1)).setText(this
                    .getItem(position).SSID.replaceAll("\"", ""));
            return v;
        }

        @Override
        public void clear() {
            super.clear();
            passwords.clear();
        }

        /**
         * Add a {@link WifiConfiguration} with password.
         *
         * @param object   {@link WifiConfiguration}
         * @param password password
         */
        public void add(final WifiConfiguration object, final String password) {
            add(object);
            passwords.put(object, password);
        }

        /**
         * Get password for {@link WifiConfiguration}.
         *
         * @param position position
         * @return password
         */
        public String getPassword(final int position) {
            WifiConfiguration wc = getItem(position);
            if (wc == null) {
                return null;
            }
            return passwords.get(wc);
        }
    }

    /**
     * Encloses the incoming string inside double quotes, if it isn't already quoted.
     *
     * @param string : the input string
     * @return a quoted string, of the form "input". If the input string is null, it returns null as
     * well.
     */
    private static String convertToQuotedString(final String string) {
        if (string == null) {
            return null;
        }
        if (TextUtils.isEmpty(string)) {
            return "";
        }
        int lastPos = string.length() - 1;
        if (lastPos < 0
                || (string.charAt(0) == '"' && string.charAt(lastPos) == '"')) {
            return string;
        }
        return '\"' + string + '\"';
    }

    /**
     * Run command as root.
     *
     * @param command command
     * @return true, if command was successfully executed
     */
    private static boolean runAsRoot(final String command) {
        Log.i(TAG, "running command as root: ", command);
        try {
            Runtime r = Runtime.getRuntime();
            Process p = r.exec("su");
            DataOutputStream d = new DataOutputStream(p.getOutputStream());
            d.writeBytes(command);
            d.writeBytes("\nexit\n");
            d.flush();
            int retval = p.waitFor();
            Log.i(TAG, "done");
            return (retval == 0);
        } catch (Exception e) {
            Log.e(TAG, "runAsRoot", e);
            return false;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        if (savedInstanceState != null) {
            mGotRoot = savedInstanceState.getBoolean(EXTRA_GOT_ROOT, true);
            mFirstLoad = savedInstanceState.getBoolean("mFirstLoad", true);
        } else {
            flushWifiPasswords();
        }

        WifiAdapter adapter = new WifiAdapter(this, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        findViewById(R.id.add).setOnClickListener(this);
        findViewById(R.id.barcode).setOnClickListener(this);
        mEtSsid = (EditText) findViewById(R.id.ssid);
        mEtPassword = (EditText) findViewById(R.id.password);
        mSpConfigs = (Spinner) findViewById(R.id.configurations);
        mSpNetType = (Spinner) findViewById(R.id.networktype);

        mSpConfigs.setAdapter(adapter);
        mSpConfigs.setOnItemSelectedListener(new OnItemSelectedListener() {
            @Override
            public void onItemSelected(final AdapterView<?> parent,
                                       final View view, final int position, final long id) {
                if (position == 0) {
                    WifiBarcodeActivity.this.mEtSsid.setText(null);
                    WifiBarcodeActivity.this.mEtSsid.setEnabled(true);
                    WifiBarcodeActivity.this.mSpNetType.setEnabled(true);
                    WifiBarcodeActivity.this.mSpNetType.setSelection(0);
                    WifiBarcodeActivity.this.mEtPassword.setText(null);
                    WifiBarcodeActivity.this.mEtPassword.setEnabled(true);
                } else {
                    WifiAdapter a = (WifiAdapter) WifiBarcodeActivity.this.mSpConfigs.getAdapter();
                    WifiConfiguration wc = a.getItem(position);
                    assert wc != null;
                    WifiBarcodeActivity.this.mEtSsid.setText(wc.SSID
                            .replaceAll("\"", ""));
                    WifiBarcodeActivity.this.mEtSsid.setEnabled(false);
                    int i = 0;
                    if (wc.allowedAuthAlgorithms
                            .get(WifiConfiguration.AuthAlgorithm.SHARED)) {
                        i = 1;
                    } else if (wc.allowedKeyManagement
                            .get(WifiConfiguration.KeyMgmt.WPA_PSK)) {
                        i = 2;
                    }
                    WifiBarcodeActivity.this.mSpNetType.setSelection(i);
                    WifiBarcodeActivity.this.mSpNetType.setEnabled(false);
                    String p = a.getPassword(position);
                    WifiBarcodeActivity.this.mEtPassword.setText(p);
                    WifiBarcodeActivity.this.mEtPassword.setEnabled(i != 0
                            && TextUtils.isEmpty(p));
                }
                WifiBarcodeActivity.this.showBarcode();
                WifiBarcodeActivity.this.findViewById(R.id.add).setVisibility(
                        View.GONE);

            }

            @Override
            public void onNothingSelected(final AdapterView<?> parent) {
                // nothing to do
            }

        });

        mSpNetType.setOnItemSelectedListener(new OnItemSelectedListener() {
            @Override
            public void onItemSelected(final AdapterView<?> parent,
                                       final View view, final int position, final long id) {
                //noinspection ConstantConditions
                String p = WifiBarcodeActivity.this.mEtPassword.getText()
                        .toString();
                WifiBarcodeActivity.this.mEtPassword.setEnabled(position != 0
                        && (WifiBarcodeActivity.this.mSpConfigs
                        .getSelectedItemPosition() == 0 || TextUtils
                        .isEmpty(p)));
            }

            @Override
            public void onNothingSelected(final AdapterView<?> parent) {
                // nothing to do
            }
        });

        barcodeSize = getResources().getInteger(R.integer.barcode_size);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onSaveInstanceState(final Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(EXTRA_GOT_ROOT, mGotRoot);
        outState.putBoolean("mFirstLoad", mFirstLoad);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getSupportMenuInflater().inflate(R.menu.menu, menu);
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        Log.d(TAG, "onOptionsItemSelected(", item.getItemId(), ")");
        switch (item.getItemId()) {
            case R.id.item_wifi_config:
                startActivity(new Intent("android.settings.WIFI_SETTINGS"));
                return true;
            case R.id.item_scan:
                try {
                    Intent intent = new Intent(
                            "com.google.zxing.client.android.SCAN");
                    // intent.setPackage("com.google.zxing.client.android");
                    intent.putExtra("SCAN_MODE", "QR_CODE_MODE");
                    startActivityForResult(intent, 0);
                } catch (ActivityNotFoundException e) {
                    Log.e(TAG, "failed launching scanner", e);
                    Builder b = new Builder(this);
                    b.setTitle(R.string.install_barcode_scanner_);
                    b.setMessage(R.string.install_barcode_scanner_hint);
                    b.setNegativeButton(android.R.string.cancel, null);
                    b.setPositiveButton(R.string.install,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(final DialogInterface dialog,
                                                    final int which) {
                                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(BARCODE_READER_URL)));
                                }
                            }
                    );
                    b.show();
                }
                return true;
            case R.id.item_about:
                startActivity(new Intent(this, About.class));
                return true;
            default:
                return false;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onActivityResult(final int requestCode, final int resultCode,
                                 final Intent intent) {
        if (requestCode == 0) {
            if (resultCode == RESULT_OK) {
                final String contents = intent.getStringExtra("SCAN_RESULT");
                Log.d(TAG, "got qr code: ", contents);
                parseResult(contents);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onClick(final View v) {
        switch (v.getId()) {
            case R.id.add:
                addWifi();
                break;
            case R.id.barcode:
                final Intent i = new Intent(this, ViewerActivity.class);
                i.putExtra(EXTRA_BARCODE, mCurrentBarcode);
                i.putExtra(EXTRA_TITLE, mEtSsid.getText().toString());
                startActivity(i);
                break;
            default:
                break;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadWifiConfigurations();
    }

    /**
     * Load wifi configurations.
     */
    private void loadWifiConfigurations() {
        WifiAdapter adapter = (WifiAdapter) mSpConfigs.getAdapter();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        assert wm != null;
        List<WifiConfiguration> wcs = wm.getConfiguredNetworks();
        String currentSSID = wm.getConnectionInfo().getSSID();
        Log.d(TAG, "currentSSID=", currentSSID);
        WifiConfiguration custom = new WifiConfiguration();
        custom.SSID = getString(R.string.custom);
        adapter.clear();
        adapter.add(custom);
        flushWifiPasswords();
        Log.d(TAG, "#wcs=", wcs == null ? "null" : wcs.size());
        if (wcs != null) {
            int selected = -1;
            for (WifiConfiguration wc : wcs) {
                adapter.add(wc, getWifiPassword(wc));
                Log.d(TAG, "wc.SSID=", wc.SSID);
                if (mFirstLoad && currentSSID != null && currentSSID.equals(wc.SSID)) {
                    selected = adapter.getCount() - 1;
                    Log.d(TAG, "selected=", selected);
                }
            }
            if (selected > 0) {
                // mFirstLoad == true
                mSpConfigs.setSelection(selected);
            }
            mFirstLoad = false;
        }
    }

    /**
     * Add wifi configuration.
     */
    private void addWifi() {
        WifiConfiguration wc = new WifiConfiguration();
        wc.allowedAuthAlgorithms.clear();
        wc.allowedGroupCiphers.clear();
        wc.allowedKeyManagement.clear();
        wc.allowedPairwiseCiphers.clear();
        wc.allowedProtocols.clear();

        //noinspection ConstantConditions
        wc.SSID = convertToQuotedString(mEtSsid.getText().toString());
        wc.hiddenSSID = true;

        //noinspection ConstantConditions
        String password = mEtPassword.getText().toString();

        switch (mSpNetType.getSelectedItemPosition()) {
            case 1: // WEP
                wc.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                wc.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
                wc.allowedAuthAlgorithms
                        .set(WifiConfiguration.AuthAlgorithm.SHARED);
                int length = password.length();
                // WEP-40, WEP-104, and 256-bit WEP (WEP-232?)
                if ((length == 10 || length == 26 || length == 58)
                        && password.matches("[0-9A-Fa-f]*")) {
                    wc.wepKeys[0] = password;
                } else {
                    wc.wepKeys[0] = '"' + password + '"';
                }
                break;
            case 2: // WPA
                wc.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                if (password.matches("[0-9A-Fa-f]{64}")) {
                    wc.preSharedKey = password;
                } else {
                    wc.preSharedKey = '"' + password + '"';
                }
                break;
            default: // OPEN
                wc.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                break;
        }

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        assert wm != null;
        int netId = wm.addNetwork(wc);
        int msg;
        final boolean ret = wm.saveConfiguration();
        if (ret) {
            wm.enableNetwork(netId, false);
            msg = R.string.wifi_added;
        } else {
            msg = R.string.wifi_failed;
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    /**
     * Parse result from QR Code.
     *
     * @param result content from qr code
     */
    private void parseResult(final String result) {
        Log.d(TAG, "parseResult(", result, ")");
        if (result == null || !result.startsWith("WIFI:")) {
            Log.e(TAG, "error parsing result: ", result);
            Toast.makeText(this, R.string.error_read_barcode, Toast.LENGTH_LONG)
                    .show();
            return;
        }

        String[] c = result.substring("WIFI:".length()).split(";", 3);
        for (String line : c) {
            if (line.startsWith("S:")) {
                mEtSsid.setText(line.substring(2));
            } else if (line.startsWith("T:NOPASS")) {
                mSpNetType.setSelection(0);
            } else if (line.startsWith("T:WEP")) {
                mSpNetType.setSelection(1);
            } else if (line.startsWith("T:WPA")) {
                mSpNetType.setSelection(2);
            } else if (line.startsWith("P:")) {
                mEtPassword.setText(line.substring(2).replaceAll(";?;$",
                        ""));
            }
        }

        mSpConfigs.setSelection(0);

        findViewById(R.id.add).setVisibility(View.VISIBLE);
    }

    /**
     * Flush wifi password cache.
     */
    private void flushWifiPasswords() {
        for (String name : SECRET_FILES) {
            deleteCacheFile(name);
        }
    }

    private void deleteCacheFile(final String name) {
        final File f = getCacheFile(name);
        if (f.exists() && !f.delete()) {
            Log.e(TAG, "error deleting file: ", f);
        }
    }

    private static File getRealCacheDir(final Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            return context.getExternalCacheDir();
        } else {
            return context.getCacheDir();
        }
    }

    /**
     * @return file holding cached wpa_supplicant.conf.
     */
    private File getCacheFile() {
        return getCacheFile("wpa_supplicant.conf");
    }

    private File getCacheFile(final String name) {
        return new File(getRealCacheDir(this), name);
    }

    private String buildCommand(String name, File target) {
        final String targetPath = target.getAbsolutePath();
        final String tpl = "pkgid=$(grep '%s' /data/system/packages.list | cut -d' ' -f2)\n"
                + "cat /data/misc/wifi/%s > '%s'\n"
                + "chown $pkgid:$pkgid '%s'\n"
                + "chmod 644 '%s'";
        return String.format(tpl, BuildConfig.APPLICATION_ID, name, targetPath, targetPath, targetPath);
    }

    private File ensureCacheFile(final String name) {
        final File target = getCacheFile(name);
        if (target.exists()) {
            return target;
        }

        final String command = buildCommand(name, target);
        if (!runAsRoot(command)) {
            return null;
        }
        return target;
    }

    private File ensureCacheFiles() {
        File target = ensureCacheFile(SECRETS_FILE_XML);
        if (target != null) {
            mXmlConfig = true;
            return new File(target.getAbsolutePath());
        } else {
            target = ensureCacheFile(SECRETS_FILE_PLAIN);
            if (target != null) {
                mXmlConfig = false;
                return new File(target.getAbsolutePath());
            } else {
                Toast.makeText(this, R.string.error_need_root, Toast.LENGTH_LONG).show();
                mGotRoot = false;
                return null;
            }
        }
    }

    private String parsePlainConfig(final File f, final String ssid) {
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            String l;
            StringBuffer sb = new StringBuffer();
            while ((l = br.readLine()) != null) {
                if (l.startsWith("network=") || l.equals("}")) {
                    String config = sb.toString();
                    // parse it
                    if (config.contains("ssid=" + ssid)) {
                        Log.d(TAG, "wifi config:");
                        Log.d(TAG, config);
                        int i = config.indexOf("wep_key0=");
                        int len;
                        if (i < 0) {
                            i = config.indexOf("psk=");
                            len = "psk=".length();
                        } else {
                            len = "wep_key0=".length();
                        }
                        if (i < 0) {
                            br.close();
                            return null;
                        }

                        br.close();
                        return config.substring(i + len + 1,
                                config.indexOf("\n", i) - 1);

                    }
                    sb = new StringBuffer();
                }
                sb.append(l).append("\n");
            }
            br.close();
        } catch (IOException e) {
            Log.e(TAG, "error reading file", e);
            Toast.makeText(this, R.string.error_read_file, Toast.LENGTH_LONG).show();
            return null;
        }
        return null;
    }

    private String parseXmlConfig(final File f, final String ssid) {
        try {
            return new XmlConfigParser().parse(new FileInputStream(f), ssid);
        } catch (IOException | XmlPullParserException e) {
            Log.e(TAG, "error reading file", e);
            Toast.makeText(this, R.string.error_read_file, Toast.LENGTH_LONG).show();
            return null;
        }
    }


    /**
     * Get WiFi password.
     *
     * @param wc {@link WifiConfiguration}
     * @return password
     */
    private String getWifiPassword(final WifiConfiguration wc) {
        Log.d(TAG, "getWifiPassword(", wc, ")");
        final File f = ensureCacheFiles();
        if (f == null) {
            return null;
        }

        if (!f.exists()) {
            Toast.makeText(this, R.string.error_read_file, Toast.LENGTH_LONG).show();
            return null;
        }

        return mXmlConfig ? parseXmlConfig(f, wc.SSID) : parsePlainConfig(f, wc.SSID);
    }

    @NonNull
    private String getBarcodeContent() {
        int type = mSpNetType.getSelectedItemPosition();
        String[] types = getResources().getStringArray(
                R.array.networktypes);
        StringBuilder sb = new StringBuilder();
        sb.append("WIFI:T:");
        sb.append(types[type]);
        sb.append(";S:");
        sb.append(mEtSsid.getText());
        sb.append(";P:");
        if (type == 0) {
            sb.append("nopass");
        } else {
            sb.append(mEtPassword.getText());
        }
        sb.append(";;");
        return sb.toString();
    }

    private void showBarcode() {
        QRCodeWriter w = new QRCodeWriter();
        try {
            final BitMatrix qrCode = w.encode(getBarcodeContent(), BarcodeFormat.QR_CODE, barcodeSize, barcodeSize);
            final int width = qrCode.getWidth();
            final int height = qrCode.getHeight();
            mCurrentBarcode = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    mCurrentBarcode.setPixel(x, y, qrCode.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            final ImageView iv = (ImageView) findViewById(R.id.barcode);
            iv.setVisibility(View.VISIBLE);
            iv.setImageBitmap(mCurrentBarcode);
            findViewById(R.id.c2e).setVisibility(View.VISIBLE);
        } catch (WriterException e) {
            Log.e(TAG, "error generating qr code", e);
        }
    }
}
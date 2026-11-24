package com.bbf.pos;

import android.Manifest;
import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.util.Base64;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION = "com.bbf.pos.USB_PERMISSION";
    private static final String DEFAULT_UPI_ID = "Vyapar.173291080735@hdfcbank";
    private static final String DEFAULT_PAYEE_NAME = "BIG BOY'S FASHION";
    private static final String SHOP_PHONE_IGNORE = "7380792557";
    private static final int LINE_WIDTH = 48;

    private WebView webView;
    private String pendingPrintText = null;
    private byte[] pendingPrintBytes = null;
    private boolean usbReceiverRegistered = false;
    private final Object usbPrintLock = new Object();
    private final ExecutorService usbExecutor = Executors.newSingleThreadExecutor();
    private volatile String lastReceiptTextCache = "";
    private volatile String lastCustomerPhoneCache = "";

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    if (granted && device != null) {
                        byte[] dataToSend = null;
                        String textToSend = null;
                        synchronized (usbPrintLock) {
                            if (pendingPrintBytes != null) {
                                dataToSend = pendingPrintBytes;
                                pendingPrintBytes = null;
                                pendingPrintText = null;
                            } else if (pendingPrintText != null) {
                                textToSend = pendingPrintText;
                                pendingPrintText = null;
                            }
                        }
                        if (dataToSend != null) {
                            enqueueBytesToUsbDevice(device, dataToSend, true);
                        } else if (textToSend != null) {
                            enqueueRawToUsbDevice(device, textToSend);
                        }
                    } else {
                        synchronized (usbPrintLock) {
                            pendingPrintBytes = null;
                            pendingPrintText = null;
                        }
                        Toast.makeText(MainActivity.this, "USB printer permission denied", Toast.LENGTH_SHORT).show();
                    }
                }
            } catch (Exception e) {
                synchronized (usbPrintLock) {
                    pendingPrintBytes = null;
                    pendingPrintText = null;
                }
                Toast.makeText(MainActivity.this, "USB receiver error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        registerUsbPermissionReceiver();

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleExternalScheme(request != null ? request.getUrl() : null);
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleExternalScheme(url != null ? Uri.parse(url) : null);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return assetLoader.shouldInterceptRequest(Uri.parse(url));
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                try {
                    request.grant(request.getResources());
                } catch (Exception ignored) {}
            }
        });

        webView.addJavascriptInterface(new AndroidPOSBridge(), "AndroidPOS");

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                try {
                    if (url != null && url.startsWith("data:")) {
                        saveDataUrlToDownloads(url, mimeType);
                        return;
                    }
                    DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                    req.setMimeType(mimeType);
                    req.addRequestHeader("User-Agent", userAgent);
                    req.setDescription("Downloading BBF POS file...");
                    String ext = (mimeType != null && mimeType.contains("csv")) ? ".csv" : ".txt";
                    String fileName = "BBF_POS_" + System.currentTimeMillis() + ext;
                    req.setTitle(fileName);
                    req.allowScanningByMediaScanner();
                    req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    if (dm != null) {
                        dm.enqueue(req);
                        Toast.makeText(MainActivity.this, "Downloading " + fileName, Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Download failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });

        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    private void captureReceiptTextFromWebViewThen(final java.util.function.Consumer<String> onTextReady) {
        if (webView == null) {
            onTextReady.accept(lastReceiptTextCache);
            return;
        }
        String js = "(function() {"
                + "var el = document.querySelector('#receiptPreview, #billPreview, .receipt-preview, .receipt-paper, .bill-box, .print-area, [data-receipt]');"
                + "if (el && el.innerText && el.innerText.trim().length > 20) return el.innerText;"
                + "var bodyText = document.body ? document.body.innerText : '';"
                + "var idx = bodyText.indexOf('BBF - BIG BOY');"
                + "if (idx >= 0) return bodyText.substring(idx);"
                + "return bodyText;"
                + "})();";
        webView.evaluateJavascript(js, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                String extracted = "";
                if (value != null && !value.equals("null")) {
                    extracted = value;
                    if (extracted.startsWith("\"") && extracted.endsWith("\"") && extracted.length() >= 2) {
                        extracted = extracted.substring(1, extracted.length() - 1)
                                .replace("\\n", "\n")
                                .replace("\\r", "")
                                .replace("\\\"", "\"")
                                .replace("\\\\", "\\");
                    }
                }
                if (extracted != null && extracted.trim().length() > 15) {
                    lastReceiptTextCache = extracted;
                    String foundCustPhone = extractCustomerPhoneOnly(extracted, null);
                    if (!foundCustPhone.isEmpty()) {
                        lastCustomerPhoneCache = foundCustPhone;
                    }
                    onTextReady.accept(extracted);
                } else {
                    onTextReady.accept(lastReceiptTextCache);
                }
            }
        });
    }

    private String normalizePhoneForWhatsApp(String rawPhone) {
        if (rawPhone == null) return "";
        String digits = rawPhone.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return "";
        if (digits.length() == 10) {
            return "91" + digits;
        }
        if (digits.startsWith("0") && digits.length() == 11) {
            return "91" + digits.substring(1);
        }
        return digits;
    }

    private boolean isShopNumber(String phoneDigits) {
        if (phoneDigits == null || phoneDigits.isEmpty()) return false;
        String clean = phoneDigits.replaceAll("[^0-9]", "");
        return clean.equals(SHOP_PHONE_IGNORE) || clean.equals("91" + SHOP_PHONE_IGNORE);
    }

    private String extractCustomerPhoneOnly(String text, Uri uri) {
        if (text != null && !text.trim().isEmpty()) {
            Matcher mCustLine = Pattern.compile("(?im)^\\s*(?:Cust|Customer|Phone|Mobile|Mob|WhatsApp|Contact)\\s*[:#\\-]?\\s*(.+)$").matcher(text);
            while (mCustLine.find()) {
                String lineVal = mCustLine.group(1);
                Matcher mNum = Pattern.compile("(\\+?91[\\s\\-]?[6-9][0-9]{9}|0?[6-9][0-9]{9}|[6-9][0-9]{9})").matcher(lineVal);
                while (mNum.find()) {
                    String norm = normalizePhoneForWhatsApp(mNum.group(1));
                    if (!norm.isEmpty() && !isShopNumber(norm)) {
                        return norm;
                    }
                }
            }

            Matcher mAnyMob = Pattern.compile("(?:\\+?91[\\s\\-]?)?([6-9][0-9]{9})").matcher(text);
            while (mAnyMob.find()) {
                String norm = normalizePhoneForWhatsApp(mAnyMob.group(1));
                if (!norm.isEmpty() && !isShopNumber(norm)) {
                    return norm;
                }
            }
        }

        if (uri != null) {
            String phoneParam = uri.getQueryParameter("phone");
            String normParam = normalizePhoneForWhatsApp(phoneParam);
            if (!normParam.isEmpty() && !isShopNumber(normParam)) {
                return normParam;
            }
            String raw = uri.toString();
            Matcher mWaMe = Pattern.compile("wa\\.me/([0-9]{10,15})").matcher(raw);
            if (mWaMe.find()) {
                String normWa = normalizePhoneForWhatsApp(mWaMe.group(1));
                if (!normWa.isEmpty() && !isShopNumber(normWa)) {
                    return normWa;
                }
            }
        }

        return "";
    }

    private boolean handleExternalScheme(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        if (scheme == null) return false;
        String lowerScheme = scheme.toLowerCase(Locale.US);

        if ("http".equals(lowerScheme) || "https".equals(lowerScheme)) {
            String host = uri.getHost();
            if ("appassets.androidplatform.net".equals(host)) {
                return false;
            }
            if ("wa.me".equals(host) || (host != null && host.endsWith("whatsapp.com"))) {
                String txt = uri.getQueryParameter("text");
                String ph = extractCustomerPhoneOnly(txt, uri);
                if (ph.isEmpty()) ph = lastCustomerPhoneCache;
                return openWhatsAppShare(txt, ph);
            }
            try {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, uri);
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(browserIntent);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        if ("whatsapp".equals(lowerScheme) || "intent".equals(lowerScheme)) {
            String textParam = uri.getQueryParameter("text");
            String raw = uri.toString();
            if (textParam == null) {
                Matcher m = Pattern.compile("[?&]text=([^&]+)").matcher(raw);
                if (m.find()) {
                    textParam = Uri.decode(m.group(1));
                }
            }
            String ph = extractCustomerPhoneOnly(textParam, uri);
            if (ph.isEmpty()) ph = lastCustomerPhoneCache;
            return openWhatsAppShare(textParam, ph);
        }

        if ("upi".equals(lowerScheme) || "tel".equals(lowerScheme) || "mailto".equals(lowerScheme)) {
            try {
                Intent appIntent = new Intent(Intent.ACTION_VIEW, uri);
                appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(appIntent);
                return true;
            } catch (Exception e) {
                Toast.makeText(this, "Supported app nahi mila", Toast.LENGTH_SHORT).show();
                return true;
            }
        }

        return false;
    }

    private Bitmap generateSimpleQrBitmap(String content, int sizePx) {
        try {
            if (content == null || content.isEmpty()) return null;
            int cells = 25;
            boolean[][] matrix = new boolean[cells][cells];
            int hash = 0x811c9dc5;
            for (int i = 0; i < content.length(); i++) {
                hash ^= content.charAt(i);
                hash *= 0x01000193;
            }

            int[][] corners = new int[][]{{0, 0}, {0, cells - 7}, {cells - 7, 0}};
            for (int[] c : corners) {
                for (int r = 0; r < 7; r++) {
                    for (int col = 0; col < 7; col++) {
                        boolean border = (r == 0 || r == 6 || col == 0 || col == 6);
                        boolean center = (r >= 2 && r <= 4 && col >= 2 && col <= 4);
                        matrix[c[0] + r][c[1] + col] = (border || center);
                    }
                }
            }

            int bitIdx = 0;
            for (int r = 0; r < cells; r++) {
                for (int col = 0; col < cells; col++) {
                    boolean inTL = (r < 8 && col < 8);
                    boolean inTR = (r < 8 && col >= cells - 8);
                    boolean inBL = (r >= cells - 8 && col < 8);
                    if (inTL || inTR || inBL) continue;
                    int mix = hash ^ (r * 31 + col * 17 + bitIdx * 7);
                    matrix[r][col] = ((mix >>> (bitIdx % 16)) & 1) == 1;
                    bitIdx++;
                }
            }

            Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            canvas.drawColor(Color.WHITE);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.BLACK);
            float cellSize = (float) sizePx / cells;
            for (int r = 0; r < cells; r++) {
                for (int col = 0; col < cells; col++) {
                    if (matrix[r][col]) {
                        canvas.drawRect(col * cellSize, r * cellSize, (col + 1) * cellSize, (r + 1) * cellSize, p);
                    }
                }
            }
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    private File generateBillPdfFile(String text) {
        try {
            File pdfDir = new File(getCacheDir(), "pdfs");
            if (!pdfDir.exists()) pdfDir.mkdirs();

            String sourceText = (text != null && !text.trim().isEmpty()) ? text : lastReceiptTextCache;
            String billNo = extractBillNumber(sourceText);
            String dateStr = extractField(sourceText, "Date");
            String staffStr = extractField(sourceText, "Staff");
            String custStr = extractField(sourceText, "Cust");
            String modeStr = extractMode(sourceText);
            if (modeStr.isEmpty() && sourceText != null && sourceText.toUpperCase(Locale.US).contains("UPI")) {
                modeStr = "UPI";
            }

            String subtotalRaw = extractNumber(sourceText, "Subtotal");
            String discountRaw = extractNumber(sourceText, "Discount|Disc");
            String gstRaw = extractNumber(sourceText, "GST");
            String grandTotalRaw = extractNumber(sourceText, "GRAND\\s*TOTAL|PAY\\s*EXACT|NET\\s*AMOUNT|TOTAL");
            String loyalty = extractField(sourceText, "Loyalty\\s*Pts\\s*Earned|Loyalty");
            List<String[]> items = parseItems(sourceText);

            double itemsSum = 0.0;
            for (String[] it : items) {
                itemsSum += parseDoubleSafe(it[2]);
            }
            double subtotalVal = !subtotalRaw.isEmpty() ? parseDoubleSafe(subtotalRaw) : itemsSum;
            double discountVal = parseDoubleSafe(discountRaw);
            double gstVal = parseDoubleSafe(gstRaw);
            double grandTotalVal = Math.max(0.0, subtotalVal - discountVal + gstVal);
            if (!grandTotalRaw.isEmpty() && di

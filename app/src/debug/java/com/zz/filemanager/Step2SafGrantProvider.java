package com.zz.filemanager;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

/**
 * Debug-only synchronous broker used by API-35 instrumentation to reproduce the narrow URI grant
 * that the system DocumentsUI would issue after ACTION_OPEN_DOCUMENT_TREE selection.
 *
 * This component is not present in release builds. It never accepts an arbitrary URI or arbitrary
 * recipient: it can grant only this app's deterministic Step 2 test tree to the standard
 * instrumentation package for this application.
 */
public final class Step2SafGrantProvider extends ContentProvider {
    public static final String AUTHORITY = "com.zz.filemanager.debug.safgrant";
    public static final String METHOD_GRANT = "grantStep2SafTree";
    public static final String EXTRA_TARGET_PACKAGE = "targetPackage";
    public static final String RESULT_GRANTED = "granted";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!METHOD_GRANT.equals(method)) {
            throw new IllegalArgumentException("Unsupported debug SAF grant method");
        }

        Context context = getContext();
        if (context == null) {
            throw new IllegalStateException("Grant provider is not attached");
        }

        String requestedPackage = extras != null
            ? extras.getString(EXTRA_TARGET_PACKAGE)
            : null;
        String expectedPackage = context.getPackageName() + ".test";
        if (!expectedPackage.equals(requestedPackage)) {
            throw new SecurityException("Debug SAF grant recipient is not the app instrumentation package");
        }

        Uri treeUri = DocumentsContract.buildTreeDocumentUri(
            Step2TestDocumentsProvider.AUTHORITY,
            Step2TestDocumentsProvider.ROOT_ID
        );
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
        context.grantUriPermission(requestedPackage, treeUri, flags);

        Bundle result = new Bundle();
        result.putBoolean(RESULT_GRANTED, true);
        return result;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException("Debug SAF grant provider exposes no data");
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Debug SAF grant provider exposes no data");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Debug SAF grant provider exposes no data");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Debug SAF grant provider exposes no data");
    }
}

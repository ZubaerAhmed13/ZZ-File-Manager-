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
 * that system DocumentsUI would issue after ACTION_OPEN_DOCUMENT_TREE selection.
 *
 * This component is absent from release builds. It accepts no arbitrary URI and no arbitrary
 * recipient: it can grant only this app's deterministic Step 2 test tree, and only to the target
 * debug application package plus that application's standard instrumentation package.
 */
public final class Step2SafGrantProvider extends ContentProvider {
    public static final String AUTHORITY = "com.zz.filemanager.debug.safgrant";
    public static final String METHOD_GRANT = "grantStep2SafTree";
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

        Uri treeUri = DocumentsContract.buildTreeDocumentUri(
            Step2TestDocumentsProvider.AUTHORITY,
            Step2TestDocumentsProvider.ROOT_ID
        );
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;

        // Android instrumentation can route ContentResolver calls under the target application's
        // identity even while test code also owns a separate test-package Context. The real system
        // picker grants the requesting app, so grant the fixed tree to the target package and also
        // to its standard instrumentation companion. Both grants are constrained to this one tree.
        String targetPackage = context.getPackageName();
        String instrumentationPackage = targetPackage + ".test";
        context.grantUriPermission(targetPackage, treeUri, flags);
        context.grantUriPermission(instrumentationPackage, treeUri, flags);

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

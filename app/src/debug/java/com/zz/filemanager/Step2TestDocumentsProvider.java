package com.zz.filemanager;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Debug-only DocumentsProvider used to certify the production SafStorageProvider on API 35.
 *
 * The provider lives in the target debug APK so the SafStorageProvider under test reaches it
 * through the target app's own ContentResolver without bypassing Android's provider boundary.
 * It is private/non-exported in the debug manifest and is absent from release builds.
 */
public final class Step2TestDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "com.zz.filemanager.test.documents";
    public static final String ROOT_ID = "root";
    public static final String ROOT_DIRECTORY_NAME = "step2-saf-documents";

    private static final String[] ROOT_PROJECTION = new String[] {
        DocumentsContract.Root.COLUMN_ROOT_ID,
        DocumentsContract.Root.COLUMN_DOCUMENT_ID,
        DocumentsContract.Root.COLUMN_TITLE,
        DocumentsContract.Root.COLUMN_SUMMARY,
        DocumentsContract.Root.COLUMN_FLAGS,
        DocumentsContract.Root.COLUMN_MIME_TYPES,
        DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
    };

    private static final String[] DOCUMENT_PROJECTION = new String[] {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    };

    @Override
    public boolean onCreate() {
        File root = rootDirectory();
        return root.exists() || root.mkdirs();
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : ROOT_PROJECTION);
        Map<String, Object> values = new HashMap<>();
        values.put(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID);
        values.put(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_ID);
        values.put(DocumentsContract.Root.COLUMN_TITLE, "Step 2 SAF Test");
        values.put(DocumentsContract.Root.COLUMN_SUMMARY, "Deterministic debug certification tree");
        values.put(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_CREATE);
        values.put(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*");
        values.put(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, rootDirectory().getUsableSpace());
        addValues(cursor, values);
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_PROJECTION);
        includeDocument(cursor, fileForDocumentId(documentId));
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
        throws FileNotFoundException {
        File parent = fileForDocumentId(parentDocumentId);
        if (!parent.isDirectory()) {
            throw new FileNotFoundException("Not a directory: " + parentDocumentId);
        }
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_PROJECTION);
        File[] children = parent.listFiles();
        if (children != null) {
            java.util.Arrays.sort(children, (left, right) ->
                left.getName().compareToIgnoreCase(right.getName()));
            for (File child : children) {
                includeDocument(cursor, child);
            }
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openDocument(
        String documentId,
        String mode,
        CancellationSignal signal
    ) throws FileNotFoundException {
        if (signal != null) {
            signal.throwIfCanceled();
        }
        File file = fileForDocumentId(documentId);
        if (!file.exists() || file.isDirectory()) {
            throw new FileNotFoundException(documentId);
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
        throws FileNotFoundException {
        requireSafeName(displayName);
        File parent = fileForDocumentId(parentDocumentId);
        if (!parent.isDirectory()) {
            throw new FileNotFoundException(parentDocumentId);
        }
        File child = new File(parent, displayName);
        if (child.exists()) {
            throw new FileNotFoundException("Already exists: " + displayName);
        }
        boolean created;
        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
            created = child.mkdir();
        } else {
            try {
                created = child.createNewFile();
            } catch (IOException error) {
                FileNotFoundException failure = new FileNotFoundException("Could not create: " + displayName);
                failure.initCause(error);
                throw failure;
            }
        }
        if (!created) {
            throw new FileNotFoundException("Could not create: " + displayName);
        }
        Context providerContext = getContext();
        if (providerContext != null) {
            providerContext.getContentResolver().notifyChange(
                DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId),
                null
            );
        }
        return documentIdFor(child);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        if (ROOT_ID.equals(documentId)) {
            throw new FileNotFoundException("Root cannot be deleted");
        }
        File file = fileForDocumentId(documentId);
        if (!file.exists()) {
            return;
        }
        File parent = file.getParentFile();
        String parentId = parent != null ? documentIdFor(parent) : null;
        if (!deleteRecursively(file)) {
            throw new FileNotFoundException("Could not delete: " + documentId);
        }
        Context providerContext = getContext();
        if (providerContext != null && parentId != null) {
            providerContext.getContentResolver().notifyChange(
                DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentId),
                null
            );
        }
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        if (ROOT_ID.equals(documentId)) {
            throw new FileNotFoundException("Root cannot be renamed");
        }
        requireSafeName(displayName);
        File file = fileForDocumentId(documentId);
        File parent = file.getParentFile();
        if (parent == null) {
            throw new FileNotFoundException(documentId);
        }
        File renamed = new File(parent, displayName);
        if (renamed.exists() || !file.renameTo(renamed)) {
            throw new FileNotFoundException("Could not rename: " + documentId);
        }
        Context providerContext = getContext();
        if (providerContext != null) {
            providerContext.getContentResolver().notifyChange(
                DocumentsContract.buildChildDocumentsUri(AUTHORITY, documentIdFor(parent)),
                null
            );
        }
        return documentIdFor(renamed);
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        try {
            File parent = canonical(fileForDocumentId(parentDocumentId));
            File child = canonical(fileForDocumentId(documentId));
            if (parent.equals(child)) {
                return false;
            }
            return child.getPath().startsWith(parent.getPath() + File.separator);
        } catch (FileNotFoundException error) {
            return false;
        }
    }

    private void includeDocument(MatrixCursor cursor, File file) throws FileNotFoundException {
        if (!file.exists()) {
            throw new FileNotFoundException(file.getPath());
        }
        boolean directory = file.isDirectory();
        int flags = DocumentsContract.Document.FLAG_SUPPORTS_DELETE
            | DocumentsContract.Document.FLAG_SUPPORTS_RENAME;
        if (directory) {
            flags |= DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE;
        } else {
            flags |= DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
        }

        Map<String, Object> values = new HashMap<>();
        values.put(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentIdFor(file));
        values.put(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            canonical(file).equals(rootDirectory()) ? "Step 2 SAF Test" : file.getName()
        );
        values.put(
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            directory ? DocumentsContract.Document.MIME_TYPE_DIR : mimeTypeFor(file)
        );
        values.put(DocumentsContract.Document.COLUMN_FLAGS, flags);
        values.put(DocumentsContract.Document.COLUMN_SIZE, directory ? null : file.length());
        values.put(
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            file.lastModified() > 0L ? file.lastModified() : null
        );
        addValues(cursor, values);
    }

    private static void addValues(MatrixCursor cursor, Map<String, Object> values) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            row.add(values.get(column));
        }
    }

    private File rootDirectory() {
        Context providerContext = getContext();
        if (providerContext == null) {
            throw new IllegalStateException("Provider not attached");
        }
        try {
            return new File(providerContext.getCacheDir(), ROOT_DIRECTORY_NAME).getCanonicalFile();
        } catch (IOException error) {
            throw new IllegalStateException("Could not resolve deterministic SAF test root", error);
        }
    }

    private File fileForDocumentId(String documentId) throws FileNotFoundException {
        if (!ROOT_ID.equals(documentId) && !documentId.startsWith(ROOT_ID + "/")) {
            throw new FileNotFoundException("Outside root: " + documentId);
        }
        File root = rootDirectory();
        if (ROOT_ID.equals(documentId)) {
            return root;
        }
        String relative = documentId.substring((ROOT_ID + "/").length());
        File candidate = canonical(new File(root, relative));
        if (!candidate.getPath().startsWith(root.getPath() + File.separator)) {
            throw new FileNotFoundException("Outside root: " + documentId);
        }
        return candidate;
    }

    private String documentIdFor(File file) throws FileNotFoundException {
        File root = rootDirectory();
        File candidate = canonical(file);
        if (candidate.equals(root)) {
            return ROOT_ID;
        }
        String prefix = root.getPath() + File.separator;
        if (!candidate.getPath().startsWith(prefix)) {
            throw new FileNotFoundException("Outside root: " + candidate.getPath());
        }
        String relative = candidate.getPath().substring(prefix.length()).replace(File.separatorChar, '/');
        return ROOT_ID + "/" + relative;
    }

    private static File canonical(File file) throws FileNotFoundException {
        try {
            return file.getCanonicalFile();
        } catch (IOException error) {
            FileNotFoundException failure = new FileNotFoundException("Could not canonicalize: " + file.getPath());
            failure.initCause(error);
            throw failure;
        }
    }

    private static void requireSafeName(String name) throws FileNotFoundException {
        if (name == null
            || name.trim().isEmpty()
            || ".".equals(name)
            || "..".equals(name)
            || name.indexOf('/') >= 0
            || name.indexOf('\\') >= 0
            || name.indexOf('\0') >= 0) {
            throw new FileNotFoundException("Invalid display name");
        }
    }

    private static boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) {
                        return false;
                    }
                }
            }
        }
        return !file.exists() || file.delete();
    }

    private static String mimeTypeFor(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String extension = dot >= 0 && dot + 1 < name.length()
            ? name.substring(dot + 1).toLowerCase(Locale.ROOT)
            : "";
        switch (extension) {
            case "txt":
            case "md":
            case "csv":
            case "log":
                return "text/plain";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "pdf":
                return "application/pdf";
            case "mp4":
                return "video/mp4";
            default:
                return "application/octet-stream";
        }
    }
}

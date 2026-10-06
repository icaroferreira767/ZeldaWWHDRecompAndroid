package org.wwhdrecomp.app;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** The same DocumentsContract/ContentResolver SAF approach used by disc setup and Backup. */
final class ExtractedGameSource implements ExtractedGame.Source {
    private final ContentResolver resolver;
    private final Uri tree;

    ExtractedGameSource(ContentResolver resolver, Uri tree) {
        this.resolver = resolver;
        this.tree = tree;
    }

    @Override
    public List<ExtractedGame.Entry> children(String id) throws IOException {
        Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
        List<ExtractedGame.Entry> entries = new ArrayList<>();
        try (Cursor c = resolver.query(uri, new String[] {Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE}, null, null, null)) {
            if (c == null) throw new IOException("Cannot read the selected folder");
            while (c.moveToNext()) {
                entries.add(new ExtractedGame.Entry(c.getString(0), c.getString(1),
                        Document.MIME_TYPE_DIR.equals(c.getString(2)), c.isNull(3) ? -1 : c.getLong(3)));
            }
        }
        return entries;
    }

    @Override
    public InputStream open(String id) throws IOException {
        return resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id));
    }
}

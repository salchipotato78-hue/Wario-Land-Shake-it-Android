package com.wario.shakeit;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Exports the game's saves to a zip the player picks, or imports one (see Saves). Opens the system's file
 * picker straight away and closes once done.
 */
public class SavesActivity extends Activity {

    static final String EXTRA_IMPORT = "import";
    private static final int REQUEST_EXPORT = 3;
    private static final int REQUEST_IMPORT = 4;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent().getBooleanExtra(EXTRA_IMPORT, false)) {
            if (Saves.gameRunning(this)) {
                notice(LauncherActivity.t("Cierra el juego antes de importar.", "Close the game before importing."));
                finish();
                return;
            }
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("*/*");
            startActivityForResult(pick, REQUEST_IMPORT);
        } else {
            if (!Saves.hasSaves(this)) {
                notice(LauncherActivity.t("Todavía no hay partidas guardadas.", "There are no saves yet."));
                finish();
                return;
            }
            Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            create.addCategory(Intent.CATEGORY_OPENABLE);
            create.setType("application/zip");
            create.putExtra(Intent.EXTRA_TITLE, "wario-partidas-"
                    + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".zip");
            startActivityForResult(create, REQUEST_EXPORT);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            finish();
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQUEST_EXPORT) {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) {
                    throw new IOException("cannot open the file");
                }
                Saves.exportTo(this, out);
                notice(LauncherActivity.t("Partidas exportadas.", "Saves exported."));
            } catch (IOException e) {
                notice(LauncherActivity.t("No se pudieron exportar las partidas.", "Could not export the saves."));
            }
        } else if (requestCode == REQUEST_IMPORT) {
            try {
                int files = Saves.importFrom(this, uri);
                notice(LauncherActivity.t("Partidas importadas (" + files + " archivos). Las anteriores se guardaron en copias.",
                        "Saves imported (" + files + " files). The previous ones were kept as backups."));
            } catch (IOException e) {
                notice(LauncherActivity.t("No se pudieron importar: " + e.getMessage(),
                        "Could not import: " + e.getMessage()));
            }
        }
        finish();
    }

    private void notice(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
}

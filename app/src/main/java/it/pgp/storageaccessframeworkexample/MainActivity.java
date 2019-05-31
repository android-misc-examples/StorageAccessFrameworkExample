package it.pgp.storageaccessframeworkexample;

import android.app.Activity;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.support.v4.provider.DocumentFile;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    List<UriPermission> uriPermissions;
    final Set<Uri> uris = new HashSet<>();

    EditText srcDirLocal,destDirExt;
    EditText targetFilename;
    TextView listedContent;
    EditText subpathToList;

    public void updateUriPermissions() {
        uris.clear();

        uriPermissions = getContentResolver().getPersistedUriPermissions();
        for (UriPermission p : uriPermissions) {
            Log.e("onCreate: ", "uriPermission: "+p.toString());
            uris.add(p.getUri());
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        updateUriPermissions();

        listedContent = findViewById(R.id.listedContent);
        targetFilename = findViewById(R.id.targetFilename);

        srcDirLocal = findViewById(R.id.srcDirLocal);
        destDirExt = findViewById(R.id.destDirExt);

        subpathToList = findViewById(R.id.subpathToList);

        findViewById(R.id.mkdirButton).setOnClickListener(v-> createFileOrDirectory(true));
        findViewById(R.id.mkfileButton).setOnClickListener(v-> createFileOrDirectory(false));
    }

    public static final int EXTSD_REQ_CODE = 42;
    public static final String BINARY_MIME_TYPE = "application/octet-stream";

    public void reqPermExtSdCard(View unused) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(intent, EXTSD_REQ_CODE);
    }

    public void onActivityResult(int requestCode, int resultCode, Intent resultData) {
        if (requestCode == EXTSD_REQ_CODE && resultCode == RESULT_OK) {
            Uri treeUri = resultData.getData();
            Log.e("EXTSD", "treeUri is "+treeUri);
            if(treeUri == null) return;
            getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION |
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

            Toast.makeText(this, "Permissions persisted for treeUri: "+treeUri, Toast.LENGTH_SHORT).show();
            updateUriPermissions();
        }
    }

    public void listExtSdCard(View unused) {
        if(!checkSAFPermissions()) return;

        String s = subpathToList.getText().toString();
        StringBuilder sb = new StringBuilder();

        Uri firstUri = uris.iterator().next();
        DocumentFile pickedDir = DocumentFile.fromTreeUri(this, firstUri);

        if(!s.isEmpty()) pickedDir = pathConcat(pickedDir,s,true);

        try {
            // List all existing files inside picked directory
            for (DocumentFile file : pickedDir.listFiles()) {
                String msg = "Found "+(file.isDirectory()?"dir ":"file ")+file.getName() + " with size " + file.length();
                sb.append(msg).append("\n");
                Log.e("EXTSD", msg);
            }
        }
        catch(Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Unable to list folder content for "+(pickedDir==null?"null":pickedDir.getUri()), Toast.LENGTH_SHORT).show();
        }
        listedContent.setText(sb.toString());
    }

    /**
     * Web source:
     * https://stackoverflow.com/questions/37966386/saf-documentfile-check-if-path-exists-without-creating-each-documentfile-at-ea
     */
    public boolean subPathExists(Uri treeUri, String subpath) { // subpath must begin with "/"
        String id = DocumentsContract.getTreeDocumentId(treeUri);

        /* id is :  "primary:namefolder" if user select a "namefolder" from internal storage.
         or CABB-5641 if user select external storage,
        */

        id = id + subpath;  // your path,  you must to ensure is consistent with that chosen by the user,

        Uri childrenUri = DocumentsContract.buildDocumentUriUsingTree(treeUri,id);
        DocumentFile childfile = DocumentFile.fromSingleUri(MainActivity.this,childrenUri);
        return childfile != null && childfile.exists();
    }


    // pathMustExist: true by default, if not supplied
    public DocumentFile pathConcat(DocumentFile dir, String subpath, boolean... pathMustExist_) {
        boolean pathMustExist = pathMustExist_.length == 0 || pathMustExist_[0]; // pathMustExist: true by default
        if(!pathMustExist) {
            String id = DocumentsContract.getTreeDocumentId(dir.getUri()) + (subpath.startsWith("/")?subpath:("/"+subpath));

            Uri childrenUri = DocumentsContract.buildDocumentUriUsingTree(dir.getUri(),id);
//        DocumentFile childfile = DocumentFile.fromSingleUri(MainActivity.this,childrenUri); // SingleDocument doesn't support nested directory creation
            DocumentFile childfile = DocumentFile.fromTreeUri(MainActivity.this,childrenUri);
            if (childfile == null) throw new NullPointerException("null childFile");
            return childfile;
        }
        else {
            if(subpath.startsWith("/")) subpath = subpath.substring(1);
            String[] splittedPath = subpath.isEmpty() ?new String[0]:subpath.split("/");
            DocumentFile current = dir;
            for (String chunk : splittedPath) {
                current = current.findFile(chunk); // cd into child directory
            }
            return current;
        }
    }

    // copy from File to DocumentFile
    public void copyFile(File srcFile, DocumentFile destDir) throws IOException {
        int efd = existsIsFileIsDir(destDir,srcFile.getName());
        if (efd != 0) throw new IOException("Target filename "+srcFile.getName()+" in directory "+destDir+" already exists");
        DocumentFile newFile = destDir.createFile(BINARY_MIME_TYPE, srcFile.getName());
        try (OutputStream out = getContentResolver().openOutputStream(newFile.getUri());
             InputStream in = new BufferedInputStream(new FileInputStream(srcFile))){
            byte[] buf = new byte[1048576];
            int bytesRead;
            while ((bytesRead = in.read(buf)) > 0)
                out.write(buf, 0, bytesRead);
        }
        catch (NullPointerException n) {
            throw new IOException(n);
        }
    }

    /**
     * Checks whether a file with filename exists in parentDir, and if so, whether it is a regular file or a directory
     * @param parentDir
     * @param filename
     * @return 0 for non-existing, 1 for file, 2 for dir
     */
    public int existsIsFileIsDir(DocumentFile parentDir, String filename) {
        DocumentFile child = parentDir.findFile(filename);
        if(child==null) return 0;
        return child.isDirectory()?2:1;
    }

    /**
     * @param srcFile file or directory to be copied
     * @param destDir srcFile will be copied INTO destDir
     * @throws IOException
     */
    public void copyFileOrDirectory(File srcFile, DocumentFile destDir) throws IOException {
        if(srcFile.isDirectory()) {
            int efd = existsIsFileIsDir(destDir,srcFile.getName());
            if (efd == 1) throw new IOException("Target path "+destDir+"/"+srcFile.getName()+" already exists and is a file");
            if (efd == 0) destDir.createDirectory(srcFile.getName());
            // FIXME remember to manually set runtime permissions for internal storage access from app settings
            File[] files = srcFile.listFiles();
            if(files==null) throw new IOException("Inaccessible path: "+srcFile);
            for (File child : files) { // copy each child INTO the newly created dir
                copyFileOrDirectory(child,pathConcat(destDir,srcFile.getName()));
            }
        }
        else copyFile(srcFile,destDir);
    }

    public void mkdirs(String subpath) {
        if (!checkSAFPermissions()) return;
        if(subpath.startsWith("/")) subpath = subpath.substring(1);
        Uri baseUri = uris.iterator().next();
        DocumentFile pickedDir = DocumentFile.fromTreeUri(this,baseUri);

        String[] splittedPath = subpath.isEmpty() ?new String[0]:subpath.split("/");
        DocumentFile currentParent = pickedDir;
        for (String chunk : splittedPath) {
            if(!subPathExists(currentParent.getUri(),"/"+chunk))
                currentParent.createDirectory(chunk);
//            currentParent = pathConcat(currentParent,chunk);
            currentParent = currentParent.findFile(chunk); // cd into child directory
        }

//        Toast.makeText(this, "Subpath created", Toast.LENGTH_SHORT).show();
    }



    public void createFileOrDirectory(boolean mkdir) {
        if (!checkSAFPermissions()) return;

        // Create a new file and write into it
        Uri baseUri = uris.iterator().next();
        String filename = targetFilename.getText().toString();
        if(filename.isEmpty()) {
            Toast.makeText(this, "Filename is empty", Toast.LENGTH_SHORT).show();
            return;
        }
        if(subPathExists(baseUri,"/"+filename)) {
            Toast.makeText(this, "File already exists", Toast.LENGTH_SHORT).show();
            return;
        }

        DocumentFile pickedDir = DocumentFile.fromTreeUri(this,baseUri);
        DocumentFile newFile;
        if(mkdir) {
//            newFile = pickedDir.createDirectory(filename);
            mkdirs(filename);
            Toast.makeText(this, "Directory created", Toast.LENGTH_SHORT).show();
        }
        else {
            newFile = pickedDir.createFile(BINARY_MIME_TYPE, filename);
            try (OutputStream out = getContentResolver().openOutputStream(newFile.getUri())){
                out.write("Test1".getBytes());
                Toast.makeText(this, "File created", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, "Exception: "+e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    public boolean checkSAFPermissions() {
        // TODO to be checked against currently requested path (if it belongs to external storage folders)
        if (uris.isEmpty()) {
            reqPermExtSdCard(null);
            Toast.makeText(this, "SAF Permissions not granted yet", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    public void listRemovableDriveRootPaths(View unused) {
//        String x = Utils.getFirstExternalStoragePath(getApplicationContext(),true);
        String x = "";
        for (String y : Utils.getAllExternalStoragePaths(getApplicationContext(),true))
            x += y+"\n";
        Toast.makeText(this, "External paths:\n"+x, Toast.LENGTH_SHORT).show();
    }

    public void copyDirFromLocalToExt(View unused) {
        if(!checkSAFPermissions()) return;
        try {
            DocumentFile baseDocumentFile = DocumentFile.fromTreeUri(MainActivity.this,uris.iterator().next());
            mkdirs(destDirExt.getText().toString());
            copyFileOrDirectory(
                    new File(srcDirLocal.getText().toString()),
                    pathConcat(baseDocumentFile,destDirExt.getText().toString()));
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }
}

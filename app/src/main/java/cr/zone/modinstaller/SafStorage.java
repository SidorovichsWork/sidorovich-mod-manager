package cr.zone.modinstaller;
import android.content.*;
import android.net.Uri;
import android.database.Cursor;
import android.provider.DocumentsContract;
import androidx.documentfile.provider.DocumentFile;
import java.io.*;
import java.util.*;

public class SafStorage implements GameStorage {
    private static class Node { Uri uri; boolean dir; Node(Uri u,boolean d){uri=u;dir=d;} }
    private final ContentResolver resolver;
    private final Uri tree;
    private final Node data;
    private final Map<String,Map<String,Node>> cache=new HashMap<>();
    public SafStorage(Context ctx,String uri) throws IOException {
        this(ctx,uri,true);
    }
    public SafStorage(Context ctx,String uri,boolean createData) throws IOException {
        resolver=ctx.getContentResolver(); tree=Uri.parse(uri);
        DocumentFile root=DocumentFile.fromTreeUri(ctx,tree);
        if(root==null)throw new IOException(Text.get("message_167"));
        DocumentFile nested=root.findFile("openxray");if(nested!=null)root=nested;
        if(root.findFile("fsgame.ltx")==null)throw new IOException(Text.get("message_168"));
        DocumentFile gd=root.findFile("gamedata");if(gd==null&&createData)gd=root.createDirectory("gamedata");
        if(gd==null||!gd.isDirectory()||!gd.canWrite())throw new IOException(Text.get("message_169"));
        data=new Node(gd.getUri(),true);
    }
    private Map<String,Node> children(Node parent)throws IOException {
        String id=parent.uri.toString();if(cache.containsKey(id))return cache.get(id);
        Map<String,Node> map=new HashMap<>();
        Uri query=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(parent.uri));
        try(Cursor c=resolver.query(query,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)) {
            if(c==null)throw new IOException(Text.get("message_170"));
            while(c.moveToNext()) {
                String name=c.getString(1),key=name.toLowerCase(Locale.ROOT);
                if(map.containsKey(key))throw new IOException(Text.get("message_171")+name);
                map.put(key,new Node(DocumentsContract.buildDocumentUriUsingTree(tree,c.getString(0)),DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))));
            }
        }
        cache.put(id,map);return map;
    }
    private Node file(String path,boolean create)throws Exception {
        String[] parts=FilesUtil.asset(path).split("/");Node parent=data;
        for(int i=0;i<parts.length;i++) {
            Map<String,Node> map=children(parent);Node child=map.get(parts[i]);
            if(child==null&&create) {
                boolean directory=i!=parts.length-1;
                Uri made=DocumentsContract.createDocument(resolver,parent.uri,directory?DocumentsContract.Document.MIME_TYPE_DIR:"application/octet-stream",parts[i]);
                if(made==null)throw new IOException(Text.get("message_005")+path);
                child=new Node(made,directory);map.put(parts[i],child);
            }
            if(child==null)return null;
            if(i!=parts.length-1&&!child.dir)throw new IOException(Text.get("message_172")+path);
            parent=child;
        }
        if(parent.dir)throw new IOException(Text.get("message_173")+path);return parent;
    }
    public InputStream read(String path)throws Exception {Node f=file(path,false);if(f==null)return null;InputStream in=resolver.openInputStream(f.uri);if(in==null)throw new IOException(Text.get("message_091")+path);return in;}
    public void write(String path,InputStream in)throws Exception {
        Node f=file(path,true);
        try(OutputStream out=resolver.openOutputStream(f.uri,"wt")){if(out==null)throw new IOException(Text.get("message_174")+path);FilesUtil.copy(in,out,Long.MAX_VALUE);}
    }
    public void delete(String path)throws Exception {Node f=file(path,false);if(f!=null&&!DocumentsContract.deleteDocument(resolver,f.uri))throw new IOException(Text.get("message_006")+path);cache.clear();}
    public String fileState(String path)throws Exception {try(InputStream in=read(path)){return in==null?"Missing":"Active";}}
}

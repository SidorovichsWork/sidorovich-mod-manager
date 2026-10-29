package cr.zone.modinstaller;

import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

/** Content-addressed library + persistent write-ahead rollback journal. */
public class ModEngine {
    private final File root, blobs, stateFile, journalFile;
    public ModEngine(File root) throws Exception {
        this.root=root; blobs=new File(root,"blobs");FilesUtil.mkdir(blobs);
        stateFile=new File(root,"state.json");journalFile=new File(root,"journal.json");
        if(!stateFile.exists())save(new JSONObject().put("mods",new JSONArray()).put("base",new JSONObject()).put("current",new JSONObject()).put("tree",""));
        if(journalFile.exists()) {JSONObject j=new JSONObject(FilesUtil.read(journalFile));if(load().optString("transaction").equals(j.optString("id")))FilesUtil.erase(journalFile);}
    }
    public JSONObject load() throws Exception {return new JSONObject(FilesUtil.read(stateFile));}
    public void save(JSONObject s) throws Exception {FilesUtil.atomicText(stateFile,s.toString());}
    public boolean recoveryNeeded(){return journalFile.exists();}
    public String recoveryTree() throws Exception{return new JSONObject(FilesUtil.read(journalFile)).getString("tree");}
    public File blob(String hash) throws IOException {if(!hash.matches("[a-f0-9]{64}"))throw new IOException(Text.get("message_135"));return new File(blobs,hash);}
    private String store(InputStream in) throws Exception {
        File temp=File.createTempFile("import-",".tmp",root);
        try {
            try(OutputStream out=new FileOutputStream(temp)){FilesUtil.copy(in,out,Math.max(0,root.getUsableSpace()-64L*1024*1024));}
            String hash=FilesUtil.sha(temp);File dest=blob(hash);
            if(!dest.exists())Files.move(temp.toPath(),dest.toPath());return hash;
        }finally{if(temp.exists())FilesUtil.erase(temp);}
    }
    public JSONObject importMod(String name, Map<String,File> sources, PackageReader.Progress progress) throws Exception {
        if(recoveryNeeded())throw new IOException(Text.get("message_136"));
        JSONObject files=new JSONObject();long size=0;int n=0;
        for(Map.Entry<String,File> e:sources.entrySet()) {
            String path=FilesUtil.asset(e.getKey());
            try(InputStream in=new FileInputStream(e.getValue())){files.put(path,store(in));}
            size+=e.getValue().length(); if(++n%40==0)progress.report(Text.get("message_137")+n+"/"+sources.size());
        }
        return new JSONObject().put("id",UUID.randomUUID().toString()).put("name",name).put("enabled",false).put("files",files).put("size",size);
    }
    public void add(JSONObject mod) throws Exception {JSONObject s=load();s.getJSONArray("mods").put(mod);save(s);}
    public static JSONObject winners(JSONArray mods) throws JSONException {
        JSONObject out=new JSONObject();
        for(int i=0;i<mods.length();i++){JSONObject mod=mods.getJSONObject(i);if(!mod.optBoolean("enabled"))continue;JSONObject files=mod.getJSONObject("files");for(String p:keys(files))out.put(p,files.getString(p));}return out;
    }
    public static List<String> keys(JSONObject o){List<String> list=new ArrayList<>();o.keys().forEachRemaining(list::add);Collections.sort(list);return list;}
    public static Set<String> conflicts(JSONArray mods) throws JSONException {
        Set<String> seen=new HashSet<>(),out=new TreeSet<>();for(int i=0;i<mods.length();i++){JSONObject m=mods.getJSONObject(i);if(!m.optBoolean("enabled"))continue;for(String p:keys(m.getJSONObject("files")))if(!seen.add(p))out.add(p);}return out;
    }
    private String liveHash(GameStorage storage,String p) throws Exception {try(InputStream in=storage.read(p)){return in==null?null:FilesUtil.sha(in);}}
    private String snapshot(GameStorage storage,String p) throws Exception {try(InputStream in=storage.read(p)){return in==null?null:store(in);}}
    private void materialize(GameStorage storage,String p,String hash) throws Exception {
        if(hash==null){storage.delete(p);if(liveHash(storage,p)!=null)throw new IOException(Text.get("message_138")+p);return;}
        File b=blob(hash);if(!b.isFile()||!hash.equals(FilesUtil.sha(b)))throw new IOException(Text.get("message_139")+p);
        try(InputStream in=new FileInputStream(b)){storage.write(p,in);}
        if(!hash.equals(liveHash(storage,p)))throw new IOException(Text.get("message_140")+p);
    }
    private static String nullable(JSONObject o,String p)throws JSONException{return o.isNull(p)?null:o.getString(p);}
    public void apply(JSONArray mods,GameStorage storage,PackageReader.Progress progress) throws Exception {
        if(recoveryNeeded())throw new IOException(Text.get("message_141"));
        JSONObject state=load(),current=state.getJSONObject("current"),next=winners(mods),base=state.getJSONObject("base");
        Set<String> paths=new TreeSet<>(keys(current));paths.addAll(keys(next));
        JSONObject before=new JSONObject(),after=new JSONObject();int count=0;
        for(String p:paths) {
            FilesUtil.asset(p);String old=current.has(p)?current.getString(p):null,newHash=next.has(p)?next.getString(p):null;
            if(Objects.equals(old,newHash))continue;
            if(current.has(p)&&!Objects.equals(old,liveHash(storage,p)))throw new IOException(Text.get("message_142")+p+Text.get("message_143"));
            progress.report(Text.get("message_144")+(++count));String previous=snapshot(storage,p);
            // When a previously unmanaged path is touched again, refresh its baseline.
            if(!current.has(p))base.put(p,previous==null?JSONObject.NULL:previous);
            before.put(p,previous==null?JSONObject.NULL:previous);
            String desired=newHash==null?nullable(base,p):newHash;after.put(p,desired==null?JSONObject.NULL:desired);
        }
        String tx=UUID.randomUUID().toString();
        JSONObject journal=new JSONObject().put("id",tx).put("tree",state.getString("tree")).put("before",before);
        FilesUtil.atomicText(journalFile,journal.toString());
        try {
            List<String> changed=keys(after);int done=0;
            for(String p:changed){progress.report(Text.get("message_145")+(++done)+"/"+changed.size()+" · "+p);materialize(storage,p,nullable(after,p));}
            state.put("mods",mods).put("current",next).put("transaction",tx);save(state);
        }catch(Exception failure) {
            try{recover(storage,progress);}catch(Exception rollback){failure.addSuppressed(rollback);throw new IOException(Text.get("message_146")+failure.getMessage(),failure);}
            throw failure;
        }
        FilesUtil.erase(journalFile);
    }
    public void recover(GameStorage storage,PackageReader.Progress progress)throws Exception {
        if(!journalFile.exists())return;
        JSONObject j=new JSONObject(FilesUtil.read(journalFile)),before=j.getJSONObject("before");int n=0;
        for(String p:keys(before)){progress.report(Text.get("message_147")+(++n)+"/"+before.length());materialize(storage,p,nullable(before,p));}
        FilesUtil.erase(journalFile);
    }
    private Set<String> references(JSONObject s)throws Exception {
        Set<String> keep=new HashSet<>();
        for(String field:List.of("base","current")){JSONObject files=s.getJSONObject(field);for(String p:keys(files))if(!files.isNull(p))keep.add(files.getString(p));}
        JSONArray mods=s.getJSONArray("mods");for(int i=0;i<mods.length();i++){JSONObject files=mods.getJSONObject(i).getJSONObject("files");for(String p:keys(files))keep.add(files.getString(p));}
        return keep;
    }
    public long collectGarbage()throws Exception {
        if(recoveryNeeded())throw new IOException(Text.get("message_148"));
        Set<String> keep=references(load());long freed=0;
        File[] files=blobs.listFiles();if(files!=null)for(File f:files)if(!keep.contains(f.getName())){long bytes=f.length();FilesUtil.erase(f);freed+=bytes;}
        return freed;
    }
    public static class KeepPlan {
        public final List<String> names=new ArrayList<>();
        public long reclaimableBytes, retainedBytes;
        private String original;
        private JSONObject next, pinnedFiles;
    }
    /** Preserve the game's current bytes; only change library ownership and references. */
    public KeepPlan previewKeepInstalled(String id)throws Exception {
        if(recoveryNeeded())throw new IOException(Text.get("message_148"));
        KeepPlan plan=new KeepPlan();plan.original=FilesUtil.read(stateFile);
        JSONObject state=new JSONObject(plan.original);JSONArray mods=state.getJSONArray("mods");
        int selected=-1;
        for(int i=0;i<mods.length();i++)if(mods.getJSONObject(i).getString("id").equals(id)){selected=i;break;}
        if(selected<0||!mods.getJSONObject(selected).optBoolean("enabled"))throw new IOException(Text.get("message_149"));
        Set<Integer> pinned=new HashSet<>();pinned.add(selected);
        Set<String> affected=new HashSet<>(keys(mods.getJSONObject(selected).getJSONObject("files")));
        // Earlier overlapping active mods cannot remain above the promoted baseline:
        // include their complete records, transitively, with an explicit UI preview.
        boolean changed;
        do{
            changed=false;
            for(int i=0;i<selected;i++){
                JSONObject m=mods.getJSONObject(i);
                if(pinned.contains(i)||!m.optBoolean("enabled"))continue;
                List<String> paths=keys(m.getJSONObject("files"));
                if(!Collections.disjoint(affected,paths)){pinned.add(i);affected.addAll(paths);changed=true;}
            }
        }while(changed);
        JSONArray remaining=new JSONArray(),records=state.optJSONArray("permanent");
        if(records==null)records=new JSONArray();
        JSONObject promoted=new JSONObject();Set<String> candidates=new HashSet<>();
        for(int i=0;i<mods.length();i++){
            JSONObject m=mods.getJSONObject(i);
            if(!pinned.contains(i)){remaining.put(m);continue;}
            plan.names.add(m.getString("name"));JSONObject files=m.getJSONObject("files");
            for(String p:keys(files)){promoted.put(p,files.getString(p));candidates.add(files.getString(p));}
            records.put(new JSONObject().put("id",m.getString("id")).put("name",m.getString("name")).put("filesCount",files.length()).put("integratedAt",System.currentTimeMillis()));
        }
        JSONObject nextCurrent=winners(remaining),base=state.getJSONObject("base");
        for(String p:keys(promoted)){
            if(base.has(p)&&!base.isNull(p))candidates.add(base.getString(p));
            if(nextCurrent.has(p))base.put(p,promoted.getString(p));
            else base.remove(p);
        }
        state.put("mods",remaining).put("current",nextCurrent).put("permanent",records);
        plan.next=state;plan.pinnedFiles=promoted;
        Set<String> keep=references(state);
        // Estimate only this operation's newly unreferenced data, not unrelated orphans.
        Set<String> previous=references(new JSONObject(plan.original));
        for(String hash:previous)if(!keep.contains(hash)){File f=blob(hash);if(f.isFile())plan.reclaimableBytes+=f.length();}
        for(String hash:candidates)if(keep.contains(hash)){File f=blob(hash);if(f.isFile())plan.retainedBytes+=f.length();}
        return plan;
    }
    public long keepInstalled(KeepPlan plan,GameStorage storage,PackageReader.Progress progress)throws Exception {
        if(recoveryNeeded())throw new IOException(Text.get("message_148"));
        if(!plan.original.equals(FilesUtil.read(stateFile)))throw new IOException(Text.get("message_150"));
        JSONObject old=new JSONObject(plan.original),current=old.getJSONObject("current"),nextCurrent=plan.next.getJSONObject("current");
        int n=0;
        for(String p:keys(plan.pinnedFiles)){
            progress.report(Text.get("message_151")+(++n)+"/"+plan.pinnedFiles.length());
            if(!current.has(p)||!Objects.equals(current.getString(p),liveHash(storage,p)))throw new IOException(Text.get("message_152")+p+Text.get("message_153"));
            if(nextCurrent.has(p)){
                File f=blob(plan.pinnedFiles.getString(p));
                if(!f.isFile()||!FilesUtil.sha(f).equals(plan.pinnedFiles.getString(p)))throw new IOException(Text.get("message_154")+p);
            }
        }
        // Commit metadata before deleting anything. After interruption, normal garbage
        // collection can finish; no write/delete call is made to the game storage.
        save(plan.next);
        progress.report(Text.get("message_155"));
        try{return collectGarbage();}
        catch(Exception e){throw new IOException(Text.get("message_156")+e.getMessage(),e);}
    }
}

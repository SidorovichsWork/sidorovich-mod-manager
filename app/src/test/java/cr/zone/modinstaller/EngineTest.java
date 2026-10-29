package cr.zone.modinstaller;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class EngineTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private ModEngine engine;
    private File root;
    private Memory game;
    static class Memory implements GameStorage {
        Map<String,byte[]> map=new HashMap<>();int writes,failAt=-1;
        public InputStream read(String p){return map.containsKey(p)?new ByteArrayInputStream(map.get(p)):null;}
        public void write(String p,InputStream in)throws Exception{
            writes++;if(writes==failAt){map.put(p,new byte[]{0});throw new IOException("Simulated disconnected provider");}
            map.put(p,in.readAllBytes());
        }
        public void delete(String p){map.remove(p);}
        void put(String p,String text){map.put(p,text.getBytes(StandardCharsets.UTF_8));}
        String get(String p){return map.containsKey(p)?new String(map.get(p),StandardCharsets.UTF_8):null;}
    }
    @Before public void setup()throws Exception{root=temp.newFolder();engine=new ModEngine(root);game=new Memory();}
    private JSONObject mod(String name,Map<String,String> content)throws Exception {
        File dir=temp.newFolder();Map<String,File> map=new TreeMap<>();int n=0;
        for(var e:content.entrySet()){File f=new File(dir,"file"+(n++));FilesUtil.atomicText(f,e.getValue());map.put(e.getKey(),f);}
        JSONObject m=engine.importMod(name,map,s->{});m.put("enabled",true);return m;
    }
    private void apply(JSONObject... mods)throws Exception{JSONArray a=new JSONArray();for(JSONObject m:mods)a.put(m);engine.apply(a,game,s->{});}
    @Test public void removalRestoresOriginalAndRemovesNewFiles()throws Exception {
        game.put("scripts/shared.script","original");
        JSONObject a=mod("A",Map.of("scripts/shared.script","a","configs/new.ltx","new"));apply(a);
        assertEquals("a",game.get("scripts/shared.script"));apply();
        assertEquals("original",game.get("scripts/shared.script"));assertNull(game.get("configs/new.ltx"));assertFalse(engine.recoveryNeeded());
    }
    @Test public void overlappingModsCanBeRemovedInAnyOrder()throws Exception {
        game.put("scripts/shared.script","original");JSONObject a=mod("A",Map.of("scripts/shared.script","a")),b=mod("B",Map.of("scripts/shared.script","b"));
        apply(a,b);assertEquals("b",game.get("scripts/shared.script"));apply(b);assertEquals("b",game.get("scripts/shared.script"));apply();assertEquals("original",game.get("scripts/shared.script"));
        apply(a,b);apply(b,a);assertEquals("a",game.get("scripts/shared.script"));apply(b);assertEquals("b",game.get("scripts/shared.script"));
    }
    @Test public void partialWriteFailureRollsBackEveryPath()throws Exception {
        game.put("configs/a.ltx","original");JSONObject a=mod("A",Map.of("configs/a.ltx","a","configs/b.ltx","b"));game.failAt=2;
        assertThrows(IOException.class,()->apply(a));assertEquals("original",game.get("configs/a.ltx"));assertNull(game.get("configs/b.ltx"));assertFalse(engine.recoveryNeeded());assertEquals(0,engine.load().getJSONObject("current").length());
    }
    @Test public void externalEditsAreNeverOverwrittenByUninstall()throws Exception {
        JSONObject a=mod("A",Map.of("scripts/a.script","a"));apply(a);game.put("scripts/a.script","user edit");assertThrows(IOException.class,()->apply());assertEquals("user edit",game.get("scripts/a.script"));assertFalse(engine.recoveryNeeded());
    }
    @Test public void baselineRefreshesAfterManualEditWhileDisabled()throws Exception {
        JSONObject a=mod("A",Map.of("scripts/a.script","a"));apply(a);apply();game.put("scripts/a.script","new baseline");apply(a);apply();assertEquals("new baseline",game.get("scripts/a.script"));
    }
    @Test public void journalSurvivesProcessDeath()throws Exception {
        JSONObject a=mod("A",Map.of("scripts/a.script","a"));String original=a.getJSONObject("files").getString("scripts/a.script");
        JSONObject before=new JSONObject().put("scripts/a.script",original).put("configs/new.ltx",JSONObject.NULL);
        FilesUtil.atomicText(new File(root,"journal.json"),new JSONObject().put("id","crashed").put("tree","mock").put("before",before).toString());game.put("scripts/a.script","partial");game.put("configs/new.ltx","partial");
        ModEngine recovered=new ModEngine(root);assertTrue(recovered.recoveryNeeded());recovered.recover(game,s->{});assertEquals("a",game.get("scripts/a.script"));assertNull(game.get("configs/new.ltx"));
    }
    @Test public void committedJournalIsNotRolledBack()throws Exception {
        JSONObject state=engine.load().put("transaction","finished");engine.save(state);
        FilesUtil.atomicText(new File(root,"journal.json"),new JSONObject().put("id","finished").put("tree","mock").put("before",new JSONObject()).toString());assertFalse(new ModEngine(root).recoveryNeeded());
    }
    @Test public void corruptedLibraryIsRejectedAndRolledBack()throws Exception {
        JSONObject a=mod("A",Map.of("scripts/a.script","a"));String hash=a.getJSONObject("files").getString("scripts/a.script");FilesUtil.atomicText(engine.blob(hash),"corrupted");assertThrows(IOException.class,()->apply(a));assertNull(game.get("scripts/a.script"));
    }
    private GameStorage readOnlyGame(){return new GameStorage(){
        public InputStream read(String p){return game.read(p);}
        public void write(String p,InputStream in){throw new AssertionError("Finalizing must never write to the game");}
        public void delete(String p){throw new AssertionError("Finalizing must never delete from the game");}
    };}
    private long keep(JSONObject m)throws Exception{return engine.keepInstalled(engine.previewKeepInstalled(m.getString("id")),readOnlyGame(),s->{});}
    @Test public void keepInstalledFreesLibraryAndBackupWithoutTouchingGame()throws Exception {
        game.put("scripts/a.script","original");JSONObject a=mod("A",Map.of("scripts/a.script","a","configs/new.ltx","new"));apply(a);
        long used=Arrays.stream(Objects.requireNonNull(new File(root,"blobs").listFiles())).mapToLong(File::length).sum();
        var plan=engine.previewKeepInstalled(a.getString("id"));assertEquals(used,plan.reclaimableBytes);assertEquals(used,engine.keepInstalled(plan,readOnlyGame(),s->{}));
        assertEquals("a",game.get("scripts/a.script"));assertEquals("new",game.get("configs/new.ltx"));assertEquals(0,new File(root,"blobs").listFiles().length);
        JSONObject state=engine.load();assertEquals(0,state.getJSONArray("mods").length());assertEquals(0,state.getJSONObject("current").length());assertEquals(0,state.getJSONObject("base").length());assertEquals("A",state.getJSONArray("permanent").getJSONObject(0).getString("name"));
        apply();assertEquals("a",game.get("scripts/a.script"));
    }
    @Test public void laterModsRestoreIntegratedBaseline()throws Exception {
        JSONObject a=mod("A",Map.of("scripts/a.script","a"));apply(a);keep(a);
        JSONObject b=mod("B",Map.of("scripts/a.script","b"));apply(b);assertEquals("b",game.get("scripts/a.script"));apply();assertEquals("a",game.get("scripts/a.script"));
    }
    @Test public void keepingLowerLayerRetainsOnlyBackupNeededByUpperLayer()throws Exception {
        game.put("scripts/shared.script","original");JSONObject a=mod("A",Map.of("scripts/shared.script","a")),b=mod("B",Map.of("scripts/shared.script","b"));apply(a,b);
        String original=engine.load().getJSONObject("base").getString("scripts/shared.script"),hashA=a.getJSONObject("files").getString("scripts/shared.script");
        var preview=engine.previewKeepInstalled(a.getString("id"));assertEquals(List.of("A"),preview.names);assertTrue(preview.retainedBytes>0);keep(a);
        assertFalse(engine.blob(original).exists());assertTrue(engine.blob(hashA).exists());assertEquals("b",game.get("scripts/shared.script"));
        b.put("enabled",false);apply(b);assertEquals("a",game.get("scripts/shared.script"));
    }
    @Test public void keepingUpperLayerExplicitlyIncludesEarlierOverlappingMods()throws Exception {
        JSONObject a=mod("A",Map.of("scripts/shared.script","a","configs/unique.ltx","unique")),b=mod("B",Map.of("scripts/shared.script","b"));apply(a,b);
        var preview=engine.previewKeepInstalled(b.getString("id"));assertEquals(List.of("A","B"),preview.names);keep(b);assertEquals(0,engine.load().getJSONArray("mods").length());assertEquals("b",game.get("scripts/shared.script"));assertEquals("unique",game.get("configs/unique.ltx"));apply();assertEquals("b",game.get("scripts/shared.script"));
    }
    @Test public void overlapClosureIsTransitiveButLeavesUnrelatedModsManaged()throws Exception {
        JSONObject a=mod("A",Map.of("configs/x.ltx","a")),d=mod("D",Map.of("configs/d.ltx","d")),b=mod("B",Map.of("configs/x.ltx","b","configs/y.ltx","b")),c=mod("C",Map.of("configs/y.ltx","c"));apply(a,d,b,c);
        var preview=engine.previewKeepInstalled(c.getString("id"));assertEquals(List.of("A","B","C"),preview.names);keep(c);
        assertEquals("D",engine.load().getJSONArray("mods").getJSONObject(0).getString("name"));apply();assertEquals("b",game.get("configs/x.ltx"));assertEquals("c",game.get("configs/y.ltx"));assertNull(game.get("configs/d.ltx"));
    }
    @Test public void disabledModsAreNotIntegratedAndCanLaterOverlayTheNewBaseline()throws Exception {
        JSONObject a=mod("A",Map.of("scripts/shared.script","a")),b=mod("B",Map.of("scripts/shared.script","b"));a.put("enabled",false);apply(a,b);
        assertEquals(List.of("B"),engine.previewKeepInstalled(b.getString("id")).names);keep(b);assertEquals(1,engine.load().getJSONArray("mods").length());a.put("enabled",true);apply(a);apply();assertEquals("b",game.get("scripts/shared.script"));
    }
    @Test public void sharedBlobUsedByAnotherRecordIsNotDeleted()throws Exception {
        JSONObject a=mod("A",Map.of("scripts/a.script","same")),b=mod("B",Map.of("configs/b.ltx","same"));b.put("enabled",false);apply(a,b);
        String hash=a.getJSONObject("files").getString("scripts/a.script");var preview=engine.previewKeepInstalled(a.getString("id"));assertEquals(0,preview.reclaimableBytes);assertEquals(4,preview.retainedBytes);assertEquals(0,keep(a));assertTrue(engine.blob(hash).exists());
    }
    @Test public void backupBlobSharedWithAnotherModIsRetained()throws Exception {
        game.put("scripts/a.script","original");JSONObject a=mod("A",Map.of("scripts/a.script","a")),b=mod("B",Map.of("configs/b.ltx","original"));b.put("enabled",false);apply(a,b);
        String shared=b.getJSONObject("files").getString("configs/b.ltx");assertEquals(8,engine.previewKeepInstalled(a.getString("id")).retainedBytes);keep(a);assertTrue(engine.blob(shared).exists());
    }
    @Test public void changedInstalledFileRejectsKeepWithoutDiscardingAnything()throws Exception {
        JSONObject a=mod("A",Map.of("scripts/a.script","a"));apply(a);var preview=engine.previewKeepInstalled(a.getString("id"));String state=FilesUtil.read(new File(root,"state.json"));game.put("scripts/a.script","external edit");
        assertThrows(IOException.class,()->engine.keepInstalled(preview,readOnlyGame(),s->{}));assertEquals(state,FilesUtil.read(new File(root,"state.json")));assertTrue(engine.blob(a.getJSONObject("files").getString("scripts/a.script")).exists());
    }
    @Test public void stalePreviewIsRejected()throws Exception {
        JSONObject a=mod("A",Map.of("scripts/a.script","a"));apply(a);var preview=engine.previewKeepInstalled(a.getString("id"));engine.save(engine.load().put("otherChange",true));assertThrows(IOException.class,()->engine.keepInstalled(preview,readOnlyGame(),s->{}));
    }
    @Test public void disabledOrUnknownModsCannotBeIntegrated()throws Exception {
        JSONObject a=mod("A",Map.of("scripts/a.script","a"));a.put("enabled",false);apply(a);assertThrows(IOException.class,()->engine.previewKeepInstalled(a.getString("id")));assertThrows(IOException.class,()->engine.previewKeepInstalled("unknown"));
    }
    @Test public void pendingRecoveryBlocksIntegratingAndGarbageCollection()throws Exception {
        JSONObject a=mod("A",Map.of("scripts/a.script","a"));apply(a);var preview=engine.previewKeepInstalled(a.getString("id"));FilesUtil.atomicText(new File(root,"journal.json"),"{}");assertThrows(IOException.class,()->engine.previewKeepInstalled(a.getString("id")));assertThrows(IOException.class,()->engine.keepInstalled(preview,readOnlyGame(),s->{}));assertThrows(IOException.class,()->engine.collectGarbage());
    }
    @Test public void coveredCorruptArchiveCannotBecomeAnotherModsBackup()throws Exception {
        JSONObject a=mod("A",Map.of("scripts/shared.script","a")),b=mod("B",Map.of("scripts/shared.script","b"));apply(a,b);FilesUtil.atomicText(engine.blob(a.getJSONObject("files").getString("scripts/shared.script")),"corrupt");var preview=engine.previewKeepInstalled(a.getString("id"));assertThrows(IOException.class,()->engine.keepInstalled(preview,readOnlyGame(),s->{}));assertEquals(2,engine.load().getJSONArray("mods").length());
    }
    @Test public void visibleVerifiedInstallCanReleaseDamagedUnusedLibrary()throws Exception {
        JSONObject a=mod("A",Map.of("scripts/a.script","a"));apply(a);FilesUtil.atomicText(engine.blob(a.getJSONObject("files").getString("scripts/a.script")),"corrupt");keep(a);assertEquals("a",game.get("scripts/a.script"));assertEquals(0,new File(root,"blobs").listFiles().length);
    }
}

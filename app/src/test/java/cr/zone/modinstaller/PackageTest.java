package cr.zone.modinstaller;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

public class PackageTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private File zip(String... names)throws Exception{File f=temp.newFile();try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(f))){for(String name:names){out.putNextEntry(new ZipEntry(name));out.write("asset".getBytes());out.closeEntry();}}return f;}
    @Test public void traversalAbsoluteAndWindowsPathsAreRejected()throws Exception {
        for(String bad:List.of("../evil","/evil","C:/evil","gamedata/../../evil","x\\..\\evil"))assertThrows(IOException.class,()->new PackageReader(temp.newFolder(),100,s->{}).zip(zip(bad)));
    }
    @Test public void caseCollisionsAreRejected()throws Exception{assertThrows(IOException.class,()->new PackageReader(temp.newFolder(),100,s->{}).zip(zip("A/file","a/FILE")));}
    @Test public void extractionBudgetIsEnforced()throws Exception{assertThrows(IOException.class,()->new PackageReader(temp.newFolder(),4,s->{}).zip(zip("asset")));}
    @Test public void actualSevenZipRoundTrip()throws Exception{
        File archive=temp.newFile(),source=temp.newFile();FilesUtil.atomicText(source,"seven zip asset");
        try(org.apache.commons.compress.archivers.sevenz.SevenZOutputFile out=new org.apache.commons.compress.archivers.sevenz.SevenZOutputFile(archive)){
            var e=out.createArchiveEntry(source,"gamedata/configs/sample.ltx");out.putArchiveEntry(e);out.write("seven zip asset".getBytes());out.closeArchiveEntry();
        }
        File stage=temp.newFolder();new PackageReader(stage,1000,s->{}).sevenZip(archive);assertEquals("seven zip asset",FilesUtil.read(new File(stage,"gamedata/configs/sample.ltx")));
    }
    @Test public void executablesAndGameEscapesAreRejected(){for(String bad:List.of("../appdata/user.ltx","bin/game.exe","configs/hook.dll","fsgame.ltx"))assertThrows(IOException.class,()->FilesUtil.asset(bad));}
    @Test public void windowsSeparatorsAndWrapperGamedataNormalize()throws Exception{assertEquals("configs/items/a.ltx",FilesUtil.asset("gamedata\\CONFIGS\\items\\a.ltx"));}
    @Test public void mcmVersionMetadataIsAnInstallableAsset()throws Exception{
        assertEquals("versions/amcm.ltx",FilesUtil.asset("gamedata\\VERSIONS\\AMCM.ltx"));
        assertThrows(IOException.class,()->FilesUtil.asset("VERSIONS/../../appdata/user.ltx"));
        assertThrows(IOException.class,()->FilesUtil.asset("VERSIONS/hook.dll"));
    }
    @Test public void everyActualMcmPathWorksThroughManualSelection()throws Exception{
        File gamedata=temp.newFolder();List<String> names;
        try(InputStream in=getClass().getResourceAsStream("/mcm-paths.txt")){
            names=new String(Objects.requireNonNull(in).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).lines().filter(s->!s.isBlank()).toList();
        }
        for(String name:names)FilesUtil.atomicText(new File(gamedata,name.replace('\\','/')),"fixture");
        Map<String,File> plan=PackageReader.manual(List.of(gamedata));assertEquals(17,plan.size());
        for(String name:names)assertTrue(plan.containsKey(FilesUtil.asset(name)));
        assertTrue(plan.containsKey("versions/amcm.ltx"));
    }
    private Fomod fixture(String resource)throws Exception {
        File root=temp.newFolder(),fomod=new File(root,"fomod");FilesUtil.mkdir(fomod);File xml=new File(fomod,"ModuleConfig.xml");
        try(InputStream in=getClass().getResourceAsStream("/"+resource)){Files.copy(Objects.requireNonNull(in),xml.toPath());}
        Document doc=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml);
        NodeList dirs=doc.getElementsByTagName("folder");
        for(int i=0;i<dirs.getLength();i++){
            Element e=(Element)dirs.item(i);File asset=new File(root,FilesUtil.path(e.getAttribute("source"))+"/configs/fixture_"+i+".ltx");FilesUtil.atomicText(asset,"source"+i);
        }
        return new Fomod(xml,p->"Missing");
    }
    @Test public void recommendedComponentsAndHiddenStepsRespectSelection()throws Exception {
        Fomod f=fixture("components.xml");List<String> names=new ArrayList<>();
        for(int i=0;i<f.steps.size();i++)if(f.prepare(i)){Fomod.Step s=f.steps.get(i);f.validate(s);for(var g:s.groups)for(var c:g.choices)if(c.selected)names.add(c.name);}
        Map<String,File> plan=f.plan();assertEquals(10,plan.size());
        assertTrue(names.containsAll(List.of("Feature 1","Feature 2","Feature 3","Feature 4","Feature 5","Feature 6","Feature 7","Feature 8","Feature 9")));
        assertFalse(names.contains("Optional patch"));assertFalse(names.contains("Hidden patch"));
    }
    @Test public void optionalCompatibilityPatchDoesNotIncludeOtherPatches()throws Exception {
        Fomod f=fixture("patches.xml");assertTrue(f.prepare(0));for(var g:f.steps.get(0).groups)for(var c:g.choices)if(c.name.equals("Compatibility patch"))c.selected=true;
        assertEquals(2,f.plan().size());
    }
    @Test public void unsupportedDependencyFailsRatherThanGuessing()throws Exception {
        File root=temp.newFolder(),xml=new File(root,"fomod/ModuleConfig.xml");FilesUtil.atomicText(xml,"<config><moduleDependencies><gameDependency version='1'/></moduleDependencies></config>");assertThrows(IOException.class,()->new Fomod(xml,p->"Missing"));
    }
    @Test public void externalXmlEntitiesAreRejected()throws Exception {
        File root=temp.newFolder(),xml=new File(root,"fomod/ModuleConfig.xml");FilesUtil.atomicText(xml,"<!DOCTYPE config [<!ENTITY ext SYSTEM 'file:///etc/passwd'>]><config/>");assertThrows(IOException.class,()->new Fomod(xml,p->"Missing"));
    }
    @Test public void conditionalFilesRespectFlagsAndPriority()throws Exception {
        File root=temp.newFolder(),xml=new File(root,"fomod/ModuleConfig.xml");
        FilesUtil.atomicText(new File(root,"base/configs/x.ltx"),"base");FilesUtil.atomicText(new File(root,"patch/configs/x.ltx"),"patch");
        FilesUtil.atomicText(xml,"<config><requiredInstallFiles><folder source='base' destination='gamedata'/></requiredInstallFiles><installSteps><installStep name='test'><optionalFileGroups><group type='SelectAll'><plugins><plugin name='required'><conditionFlags><flag name='patch'>yes</flag></conditionFlags><typeDescriptor><type name='Required'/></typeDescriptor></plugin></plugins></group></optionalFileGroups></installStep></installSteps><conditionalFileInstalls><patterns><pattern><dependencies><flagDependency flag='patch' value='yes'/></dependencies><files><folder source='patch' destination='gamedata' priority='10'/></files></pattern></patterns></conditionalFileInstalls></config>");
        Fomod f=new Fomod(xml,p->"Missing");f.prepare(0);assertEquals("patch",FilesUtil.read(f.plan().get("configs/x.ltx")));
    }
}

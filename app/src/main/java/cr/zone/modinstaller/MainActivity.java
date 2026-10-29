package cr.zone.modinstaller;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.*;
import android.widget.*;
import androidx.documentfile.provider.DocumentFile;
import org.json.*;
import java.io.*;
import java.util.*;

public class MainActivity extends Activity {
    @Override protected void attachBaseContext(Context context) { super.attachBaseContext(ModApplication.localized(context)); }

    private void chooseLanguage() {
        if(WorkService.busy)return;
        if(dirty){showError(Text.get("message_053"));return;}
        String[] codes={"en","es","ru"};
        String current=ModApplication.language(this);
        int selected=Arrays.asList(codes).indexOf(current);
        new AlertDialog.Builder(this).setTitle(getString(R.string.language))
            .setSingleChoiceItems(new String[]{"English","Español","Русский"},selected,(dialog,which)->{
                dialog.dismiss();
                if(!codes[which].equals(current)){
                    ModApplication.changeLanguage(this,codes[which]);
                    recreate();
                }
            }).setNegativeButton(android.R.string.cancel,null).show();
    }
    private static final int GAME=10,ARCHIVE=11,FOLDER=12;
    private static final int BG=0xff111713,CARD=0xff1d2720,TEXT=0xffeef2e9,MUTED=0xffaab7a9,ACCENT=0xffb8d885;
    private ModEngine engine;
    private JSONArray mods;
    private JSONArray permanent=new JSONArray();
    private String tree="";
    private LinearLayout content;
    private TextView statusView;
    private ProgressBar progress;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private int lastRevision=-1;
    private String screen="home";
    private Fomod fomod;
    private File stage;
    private String importName;
    private Map<String,File> plan;
    private int stepIndex;
    private boolean dirty;
    private volatile boolean imported,packageReady;
    private volatile ModEngine.KeepPlan keepPreview;
    private final Runnable ticker=new Runnable(){public void run(){
        if(statusView!=null)statusView.setText(WorkService.status);
        if(progress!=null)progress.setVisibility(WorkService.busy?View.VISIBLE:View.GONE);
        if(lastRevision!=WorkService.revision&&!WorkService.busy){lastRevision=WorkService.revision;
            if(!WorkService.error.isEmpty()){showError(WorkService.error);WorkService.error="";}
            if(keepPreview!=null){ModEngine.KeepPlan preview=keepPreview;keepPreview=null;try{load();showHome();confirmKeep(preview);}catch(Exception e){showError(e.getMessage());}}
            else if(packageReady){packageReady=false;configurePackage();}
            else if(imported){imported=false;try{load();showHome();}catch(Exception e){showError(e.getMessage());}}
            else if(screen.equals("home")){try{load();showHome();}catch(Exception e){showError(e.getMessage());}}
        }
        handler.postDelayed(this,700);
    }};
    @Override public void onCreate(Bundle state){super.onCreate(state);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        try{File root=new File(getExternalFilesDir(null),"library");engine=new ModEngine(root);load();showHome();}
        catch(Exception e){showError(e.getMessage());}
    }
    @Override public void onResume(){super.onResume();handler.post(ticker);}
    @Override public void onPause(){handler.removeCallbacks(ticker);super.onPause();}
    private void load()throws Exception{JSONObject s=engine.load();mods=s.getJSONArray("mods");permanent=s.optJSONArray("permanent");if(permanent==null)permanent=new JSONArray();tree=s.optString("tree");dirty=false;}
    private int dp(int n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
    private TextView label(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(5),0,dp(5));return t;}
    private void heading(String s){TextView t=label(s,24,TEXT);t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);content.addView(t);}
    private void note(String s){content.addView(label(s,14,MUTED));}
    private GradientDrawable shape(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(16));return d;}
    private void button(LinearLayout parent,String text,Runnable action,boolean enabled){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextColor(TEXT);b.setMinHeight(dp(48));b.setEnabled(enabled);b.setOnClickListener(v->{try{action.run();}catch(Exception e){showError(e.getMessage());}});parent.addView(b,new LinearLayout.LayoutParams(-1,-2));}
    private void button(String text,Runnable action){button(content,text,action,!WorkService.busy);}
    private void page(String name){screen=name;LinearLayout outer=new LinearLayout(this);outer.setOrientation(LinearLayout.VERTICAL);outer.setBackgroundColor(BG);
        outer.setPadding(dp(20),dp(16),dp(20),dp(12));
        outer.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(dp(20)+insets.getSystemWindowInsetLeft(),dp(16)+insets.getSystemWindowInsetTop(),dp(20)+insets.getSystemWindowInsetRight(),dp(12)+insets.getSystemWindowInsetBottom());return insets;});
        TextView brand=label(getString(R.string.app_name),16,ACCENT);brand.setLetterSpacing(.02f);outer.addView(brand);
        ScrollView scroll=new ScrollView(this);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(0,dp(10),0,dp(18));scroll.addView(content);outer.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);progress.setVisibility(WorkService.busy?View.VISIBLE:View.GONE);outer.addView(progress,new LinearLayout.LayoutParams(-1,dp(4)));
        statusView=label(WorkService.status,12,ACCENT);statusView.setMaxLines(3);outer.addView(statusView);setContentView(outer);
    }
    private void run(String label,WorkService.Task task){if(WorkService.busy)return;
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},22);
        WorkService.launch(this,label,task);
        if(screen.equals("home"))showHome();
    }
    private void showHome(){page("home");heading(Text.get("message_025"));note(Text.get("message_026"));
        button(getString(R.string.language)+" · English / Español / Русский",this::chooseLanguage);
        note(getString(R.string.language_hint));
        if(engine==null)return;
        if(engine.recoveryNeeded()){
            note(Text.get("message_027"));
            button(Text.get("message_028"),()->run(Text.get("message_029"),()->{engine.recover(new SafStorage(this,engine.recoveryTree()),WorkService::progress);WorkService.status=Text.get("message_030");}));return;
        }
        note(tree.isEmpty()?Text.get("message_031"):Text.get("message_032"));
        button(tree.isEmpty()?Text.get("message_033"):Text.get("message_034"),()->{
            if(mods.length()>0){showError(Text.get("message_035"));return;}
            picker(GAME,true);
        });
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setBackground(shape(CARD));card.setPadding(dp(14),dp(8),dp(14),dp(12));
        TextView title=label(Text.get("message_036"),20,TEXT);title.setTypeface(null,Typeface.BOLD);card.addView(title);
        card.addView(label(Text.get("message_037"),13,MUTED));
        for(int i=0;i<mods.length();i++){
            final int pos=i;
            try{
                JSONObject mod=mods.getJSONObject(i);CheckBox check=new CheckBox(this);check.setText(mod.getString("name"));check.setTextColor(TEXT);check.setChecked(mod.optBoolean("enabled"));check.setEnabled(!WorkService.busy);
                check.setOnCheckedChangeListener((v,on)->{try{mod.put("enabled",on);dirty=true;}catch(Exception e){showError(e.getMessage());}});card.addView(check);
                card.addView(label(mod.getJSONObject("files").length()+Text.get("message_038")+android.text.format.Formatter.formatShortFileSize(this,mod.optLong("size")),12,MUTED));
                LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);
                for(String text:new String[]{"↑","↓",Text.get("message_039")}){Button b=new Button(this);b.setText(text);b.setTextColor(TEXT);b.setAllCaps(false);b.setEnabled(!WorkService.busy);b.setOnClickListener(v->{try{if(text.equals(Text.get("message_039")))removeMod(pos);else moveMod(pos,text.equals("↑")?-1:1);}catch(Exception e){showError(e.getMessage());}});actions.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));}card.addView(actions);
                if(mod.optBoolean("enabled"))button(card,Text.get("message_040"),()->previewKeep(pos),!WorkService.busy);
            }catch(Exception e){showError(e.getMessage());}
        }
        if(mods.length()==0)card.addView(label(Text.get("message_041"),14,MUTED));content.addView(card);
        if(permanent.length()>0){
            content.addView(label(Text.get("message_042"),20,ACCENT));
            note(Text.get("message_043"));
            for(int i=0;i<permanent.length();i++)note("✓ "+permanent.optJSONObject(i).optString("name"));
        }
        button(Text.get("message_044"),()->{if(requireTarget())picker(ARCHIVE,false);});
        button(Text.get("message_045"),()->{if(requireTarget())picker(FOLDER,true);});
        button(Text.get("message_046"),()->{if(requireTarget())previewApply();});
        button(Text.get("message_047"),this::help);
        button(Text.get("message_048"),()->run(Text.get("message_049"),()->{File imports=new File(getExternalFilesDir(null),"imports");if(imports.exists())FilesUtil.erase(imports);engine.collectGarbage();WorkService.status=Text.get("message_050");}));
        note(Text.get("message_051"));
    }
    private boolean requireTarget(){if(tree.isEmpty()){showError(Text.get("message_052"));return false;}return true;}
    private void previewKeep(int pos){
        if(dirty){showError(Text.get("message_053"));return;}
        if(!requireTarget())return;
        try{String id=mods.getJSONObject(pos).getString("id");run(Text.get("message_054"),()->{keepPreview=engine.previewKeepInstalled(id);WorkService.status=Text.get("message_055");});}catch(Exception e){showError(e.getMessage());}
    }
    private void confirmKeep(ModEngine.KeepPlan preview){
        StringBuilder message=new StringBuilder(Text.get("message_056"));
        for(String name:preview.names)message.append("• ").append(name).append('\n');
        if(preview.names.size()>1)message.append(Text.get("message_057"));
        message.append(Text.get("message_058")).append(android.text.format.Formatter.formatShortFileSize(this,preview.reclaimableBytes));
        if(preview.retainedBytes>0)message.append(Text.get("message_059")).append(android.text.format.Formatter.formatShortFileSize(this,preview.retainedBytes));
        message.append(Text.get("message_060"));
        new AlertDialog.Builder(this).setTitle(Text.get("message_040")).setMessage(message).setNegativeButton(Text.get("message_061"),null).setPositiveButton(Text.get("message_062"),(d,w)->{
            String uri=tree;run(Text.get("message_063"),()->{long freed=engine.keepInstalled(preview,new SafStorage(this,uri,false),WorkService::progress);WorkService.status=Text.get("message_064")+android.text.format.Formatter.formatShortFileSize(this,freed)+Text.get("message_065");});
        }).show();
    }
    private void moveMod(int p,int delta)throws Exception{int n=p+delta;if(n<0||n>=mods.length())return;JSONArray next=new JSONArray();for(int i=0;i<mods.length();i++)next.put(mods.get(i==p?n:i==n?p:i));mods=next;dirty=true;showHome();}
    private void removeMod(int pos)throws Exception {
        JSONObject stored=engine.load();String id=mods.getJSONObject(pos).getString("id");JSONArray persisted=stored.getJSONArray("mods");
        for(int i=0;i<persisted.length();i++)if(persisted.getJSONObject(i).getString("id").equals(id)&&persisted.getJSONObject(i).optBoolean("enabled")){showError(Text.get("message_066"));return;}
        if(dirty){showError(Text.get("message_053"));return;}
        new AlertDialog.Builder(this).setTitle(Text.get("message_067")).setMessage(Text.get("message_068")).setNegativeButton(Text.get("message_061"),null).setPositiveButton(Text.get("message_039"),(d,w)->{try{mods.remove(pos);stored.put("mods",mods);run(Text.get("message_069"),()->{engine.save(stored);long freed=engine.collectGarbage();WorkService.status=Text.get("message_070")+android.text.format.Formatter.formatShortFileSize(this,freed)+Text.get("message_065");});}catch(Exception e){showError(e.getMessage());}}).show();
    }
    private void previewApply(){try{
        Set<String> conflicts=ModEngine.conflicts(mods);StringBuilder message=new StringBuilder(Text.get("message_071"));
        message.append(conflicts.size()).append(Text.get("message_072"));int n=0;for(String p:conflicts){if(n++==12){message.append("\n…");break;}message.append("\n• ").append(p);}
        message.append(Text.get("message_073"));
        new AlertDialog.Builder(this).setTitle(Text.get("message_074")).setMessage(message).setNegativeButton(Text.get("message_075"),null).setPositiveButton(Text.get("message_076"),(d,w)->{try{JSONArray next=new JSONArray(mods.toString());String uri=tree;run(Text.get("message_077"),()->{engine.apply(next,new SafStorage(this,uri),WorkService::progress);WorkService.status=Text.get("message_078");});}catch(Exception e){showError(e.getMessage());}}).show();
    }catch(Exception e){showError(e.getMessage());}}
    private void picker(int code,boolean directory){if(dirty){showError(Text.get("message_053"));return;}Intent i=new Intent(directory?Intent.ACTION_OPEN_DOCUMENT_TREE:Intent.ACTION_OPEN_DOCUMENT);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);if(!directory){i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);}startActivityForResult(i,code);}
    @Override protected void onActivityResult(int request,int result,Intent intent){super.onActivityResult(request,result,intent);if(result!=RESULT_OK||intent==null||intent.getData()==null)return;Uri uri=intent.getData();
        if(request==GAME){try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);run(Text.get("message_079"),()->{new SafStorage(this,uri.toString());JSONObject s=engine.load();s.put("tree",uri.toString()).put("base",new JSONObject()).put("current",new JSONObject()).put("permanent",new JSONArray());engine.save(s);WorkService.status=Text.get("message_080");});}catch(Exception e){showError(e.getMessage());}}
        else if(request==ARCHIVE||request==FOLDER)importPackage(uri,request==FOLDER);
    }
    private String displayName(Uri uri){try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){}return Text.get("message_081");}
    private void importPackage(Uri uri,boolean folder){importName=folder?Text.get("message_082"):displayName(uri);run(Text.get("message_083"),()->{
        File imports=new File(getExternalFilesDir(null),"imports");FilesUtil.mkdir(imports);stage=new File(imports,UUID.randomUUID().toString());FilesUtil.mkdir(stage);
        long budget=Math.max(0,stage.getUsableSpace()-128L*1024*1024);
        if(folder){DocumentFile src=DocumentFile.fromTreeUri(this,uri);if(src==null)throw new IOException(Text.get("message_084"));importName=src.getName();copyFolder(src,stage,new long[]{budget,0});}
        else{
            File archive=new File(imports,"archive-"+UUID.randomUUID());
            try{
                try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(archive)){if(in==null)throw new IOException(Text.get("message_085"));FilesUtil.copy(in,out,budget);}
                PackageReader reader=new PackageReader(stage,Math.max(0,stage.getUsableSpace()-128L*1024*1024),WorkService::progress);
                byte[] magic=new byte[6];try(InputStream in=new FileInputStream(archive)){if(in.read(magic)<4)throw new IOException(Text.get("message_086"));}
                if(magic[0]=='P'&&magic[1]=='K')reader.zip(archive);
                else if(magic[0]==0x37&&magic[1]==0x7a&&magic[2]==(byte)0xbc)reader.sevenZip(archive);
                else throw new IOException(Text.get("message_087"));
            }finally{FilesUtil.erase(archive);}
        }
        WorkService.status=Text.get("message_088");packageReady=true;
    });}
    private void copyFolder(DocumentFile source,File out,long[] quota)throws Exception {
        FilesUtil.mkdir(out);
        for(DocumentFile child:source.listFiles()){
            if(++quota[1]>PackageReader.MAX_ENTRIES)throw new IOException(Text.get("message_089"));String name=FilesUtil.path(Objects.requireNonNull(child.getName()));if(name.contains("/")||name.isEmpty())throw new IOException(Text.get("message_090"));File dest=new File(out,name);
            if(child.isDirectory())copyFolder(child,dest,quota);
            else try(InputStream in=getContentResolver().openInputStream(child.getUri());OutputStream os=new FileOutputStream(dest)){if(in==null)throw new IOException(Text.get("message_091")+name);quota[0]-=FilesUtil.copy(in,os,quota[0]);}
            if(quota[1]%60==0)WorkService.progress(Text.get("message_092")+quota[1]+Text.get("message_093"));
        }
    }
    private void configurePackage(){page("configure");heading(Text.get("message_094"));note(importName);
        List<File> installers=PackageReader.find(stage,"ModuleConfig.xml");
        if(!installers.isEmpty()){
            note(Text.get("message_095"));
            for(File xml:installers)button(Text.get("message_096")+stage.toPath().relativize(xml.toPath()),()->{
                try{SafStorage storage=new SafStorage(this,tree);fomod=new Fomod(xml,storage::fileState);stepIndex=0;showStep();}catch(Exception e){showError(e.getMessage());}
            });
        }
        button(Text.get("message_097"),this::manualOptions);button(Text.get("message_098"),this::showHome);
    }
    private void showStep(){try{
        while(stepIndex<fomod.steps.size()&&!fomod.prepare(stepIndex))stepIndex++;
        if(stepIndex>=fomod.steps.size()){plan=fomod.plan();reviewImport();return;}
        Fomod.Step step=fomod.steps.get(stepIndex);page("fomod");heading(step.name);note(fomod.name+Text.get("message_099")+(stepIndex+1)+Text.get("message_100")+fomod.steps.size());
        for(Fomod.Group group:step.groups){content.addView(label(group.name,19,ACCENT));note(ruleLabel(group.rule));List<CheckBox> boxes=new ArrayList<>();
            for(Fomod.Choice choice:group.choices){CheckBox check=new CheckBox(this);check.setText(choice.name+(choice.type.equals("Required")?Text.get("message_101"):choice.type.equals("Recommended")?Text.get("message_102"):""));check.setTextColor(TEXT);check.setChecked(choice.selected);check.setEnabled(!choice.type.equals("Required")&&!choice.type.equals("NotUsable")&&!group.rule.equals("SelectAll"));boxes.add(check);content.addView(check);if(!choice.description.isEmpty())note(choice.description);
                check.setOnCheckedChangeListener((v,on)->{choice.selected=on;if(on&&(group.rule.equals("SelectExactlyOne")||group.rule.equals("SelectAtMostOne"))){for(int i=0;i<group.choices.size();i++)if(group.choices.get(i)!=choice&&!group.choices.get(i).type.equals("Required")){group.choices.get(i).selected=false;boxes.get(i).setChecked(false);}}});
            }
        }
        button(Text.get("message_103"),()->{try{fomod.validate(step);stepIndex++;showStep();}catch(Exception e){showError(e.getMessage());}});button(Text.get("message_104"),this::configurePackage);
    }catch(Exception e){showError(e.getMessage());configurePackage();}}
    private String ruleLabel(String rule){return switch(rule){case "SelectAll"->Text.get("message_105");case "SelectExactlyOne"->Text.get("message_106");case "SelectAtLeastOne"->Text.get("message_107");case "SelectAtMostOne"->Text.get("message_108");default->Text.get("message_109");};}
    private void manualOptions(){page("manual");heading(Text.get("message_110"));note(Text.get("message_111"));
        List<File> roots=PackageReader.find(stage,"gamedata");roots.removeIf(f->!f.isDirectory());roots.sort(Comparator.comparing(File::getPath));
        if(roots.isEmpty()){findLoose(stage,roots);}
        List<CheckBox> boxes=new ArrayList<>();for(File root:roots){CheckBox c=new CheckBox(this);String path=stage.toPath().relativize(root.toPath()).toString();c.setText(path.isEmpty()?Text.get("message_112"):path);c.setTextColor(TEXT);c.setChecked(roots.size()==1);boxes.add(c);content.addView(c);}
        if(roots.isEmpty())note(Text.get("message_113"));
        button(Text.get("message_103"),()->{try{List<File> chosen=new ArrayList<>();for(int i=0;i<roots.size();i++)if(boxes.get(i).isChecked())chosen.add(roots.get(i));if(chosen.isEmpty())throw new IOException(Text.get("message_114"));manualOrder(chosen);}catch(Exception e){showError(e.getMessage());}});button(Text.get("message_075"),this::configurePackage);
    }
    private void manualOrder(List<File> chosen){page("order");heading(Text.get("message_115"));note(Text.get("message_116"));
        for(int i=0;i<chosen.size();i++){
            final int pos=i;note((i+1)+" · "+stage.toPath().relativize(chosen.get(i).toPath()));LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
            for(int delta:new int[]{-1,1}){Button b=new Button(this);b.setText(delta<0?Text.get("message_117"):Text.get("message_118"));b.setTextColor(TEXT);b.setAllCaps(false);b.setEnabled(pos+delta>=0&&pos+delta<chosen.size());b.setOnClickListener(v->{Collections.swap(chosen,pos,pos+delta);manualOrder(chosen);});row.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));}content.addView(row);
        }
        button(Text.get("message_119"),()->{try{plan=PackageReader.manual(chosen);reviewImport();}catch(Exception e){showError(e.getMessage());}});button(Text.get("message_120"),this::manualOptions);
    }
    private void findLoose(File root,List<File> roots){File[] files=root.listFiles();if(files==null)return;for(File f:files)if(f.isDirectory()&&Set.of("configs","scripts","textures","meshes","sounds","shaders","anims","particles","ai","spawns","levels","versions").contains(f.getName().toLowerCase(Locale.ROOT))){roots.add(root);return;}for(File f:files)if(f.isDirectory())findLoose(f,roots);}
    private void reviewImport(){page("review");heading(Text.get("message_121"));long size=0;for(File f:plan.values())size+=f.length();note(plan.size()+Text.get("message_038")+android.text.format.Formatter.formatShortFileSize(this,size));
        EditText name=new EditText(this);name.setText(importName);name.setTextColor(TEXT);name.setSingleLine(true);content.addView(name);note(Text.get("message_122"));
        note(Text.get("message_123"));
        button(Text.get("message_124"),()->{String title=name.getText().toString().trim();if(title.isEmpty()){showError(Text.get("message_125"));return;}Map<String,File> files=new TreeMap<>(plan);File staging=stage;run(Text.get("message_126"),()->{JSONObject m=engine.importMod(title,files,WorkService::progress);engine.add(m);FilesUtil.erase(staging);WorkService.status=Text.get("message_127");imported=true;});});button(Text.get("message_128"),this::configurePackage);
    }
    private void help(){new AlertDialog.Builder(this).setTitle(Text.get("message_129")).setMessage(Text.get("message_130")).setPositiveButton(Text.get("message_131"),null).show();}
    private void showError(String message){if(!isFinishing())new AlertDialog.Builder(this).setTitle(Text.get("message_132")).setMessage(message==null?Text.get("message_133"):message).setPositiveButton(Text.get("message_131"),null).show();}
    @Override public void onBackPressed(){if(WorkService.busy){new AlertDialog.Builder(this).setMessage(Text.get("message_134")).setPositiveButton(Text.get("message_131"),null).show();}else if(!screen.equals("home"))showHome();else super.onBackPressed();}
}

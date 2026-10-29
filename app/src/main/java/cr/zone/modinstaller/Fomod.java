package cr.zone.modinstaller;

import java.io.*;
import java.util.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;

/** Declarative FOMOD only. No installer code or script is ever executed. */
public class Fomod {
    public interface Environment { String fileState(String path) throws Exception; }
    public static class Choice {
        public Element element;
        public String name, description, type;
        public boolean selected;
    }
    public static class Group {
        public String name, rule;
        public List<Choice> choices = new ArrayList<>();
    }
    public static class Step {
        public Element element;
        public String name;
        public List<Group> groups = new ArrayList<>();
    }
    private static class Assignment {
        Element element; int priority;
        Assignment(Element e) { element = e; priority = Integer.parseInt(attr(e,"priority","0")); }
    }
    private final Element config;
    private final Map<String, File> index;
    private final Environment environment;
    public final List<Step> steps = new ArrayList<>();
    public final Map<String,String> flags = new HashMap<>();
    public final String name;

    public Fomod(File xml, Environment environment) throws Exception {
        if (xml.length() > 2 * 1024 * 1024) throw new IOException(Text.get("message_008"));
        byte[] xmlBytes = java.nio.file.Files.readAllBytes(xml.toPath());
        // Keep the original encoding (real installers include both UTF-8 and UTF-16).
        String declarations = new String(xmlBytes, java.nio.charset.StandardCharsets.ISO_8859_1).replace("\u0000", "").toUpperCase(Locale.ROOT);
        if (declarations.contains("<!DOCTYPE") || declarations.contains("<!ENTITY")) throw new IOException(Text.get("message_009"));
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setExpandEntityReferences(false);
        config = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xmlBytes)).getDocumentElement();
        if (!config.getTagName().equals("config")) throw new IOException(Text.get("message_010"));
        this.environment = environment;
        index = PackageReader.index(xml.getParentFile().getParentFile());
        name = text(child(config,"moduleName"), Text.get("message_011"));
        for (Element stepEl : ordered(child(config,"installSteps"),"installStep")) {
            Step step = new Step(); step.element = stepEl; step.name = attr(stepEl,"name",Text.get("message_012"));
            for (Element groupEl : ordered(child(stepEl,"optionalFileGroups"),"group")) {
                Group group = new Group(); group.name = attr(groupEl,"name",Text.get("message_013")); group.rule = attr(groupEl,"type","SelectAny");
                if (!Set.of("SelectAny","SelectAll","SelectExactlyOne","SelectAtMostOne","SelectAtLeastOne").contains(group.rule)) throw new IOException(Text.get("message_014") + group.rule);
                for (Element plugin : ordered(child(groupEl,"plugins"),"plugin")) {
                    Choice c = new Choice(); c.element = plugin; c.name = attr(plugin,"name",Text.get("message_015")); c.description = text(child(plugin,"description"), ""); c.type = "Optional"; group.choices.add(c);
                }
                step.groups.add(group);
            }
            steps.add(step);
        }
        Element deps = child(config,"moduleDependencies");
        if (deps != null && !dependencies(deps)) throw new IOException(Text.get("message_016"));
    }
    public static Element child(Element parent, String tag) { for (Element e : children(parent,null)) if (e.getTagName().equals(tag)) return e; return null; }
    public static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>(); if (parent == null) return out;
        NodeList list = parent.getChildNodes();
        for (int i=0;i<list.getLength();i++) if (list.item(i) instanceof Element e && (tag == null || e.getTagName().equals(tag))) out.add(e);
        return out;
    }
    private static List<Element> ordered(Element parent, String tag) {
        List<Element> list = children(parent,tag); String order = attr(parent,"order","Explicit");
        if (order.equals("Ascending")) list.sort(Comparator.comparing(e -> attr(e,"name","")));
        if (order.equals("Descending")) list.sort(Comparator.comparing((Element e) -> attr(e,"name","")).reversed()); return list;
    }
    private static String attr(Element e, String key, String fallback) { return e != null && e.hasAttribute(key) ? e.getAttribute(key) : fallback; }
    private static String text(Element e, String fallback) { return e == null ? fallback : e.getTextContent().trim(); }
    public boolean dependencies(Element parent) throws Exception {
        List<Boolean> values = new ArrayList<>();
        for (Element e : children(parent,null)) {
            switch (e.getTagName()) {
                case "dependencies": values.add(dependencies(e)); break;
                case "flagDependency": values.add(attr(e,"value","").equals(flags.get(attr(e,"flag","")))); break;
                case "fileDependency": values.add(attr(e,"state","Active").equals(environment.fileState(FilesUtil.path(attr(e,"file",""))))); break;
                default: throw new IOException(Text.get("message_017") + e.getTagName() + Text.get("message_018"));
            }
        }
        if (values.isEmpty()) return true;
        return attr(parent,"operator","And").equals("Or") ? values.contains(true) : !values.contains(false);
    }
    public void flagsBefore(int stepIndex) {
        flags.clear();
        for(int i=0;i<stepIndex;i++) for(Group g:steps.get(i).groups) for(Choice c:g.choices) if(c.selected) applyFlags(c);
    }
    private void applyFlags(Choice c) { for (Element e : children(child(c.element,"conditionFlags"),"flag")) flags.put(attr(e,"name",""),text(e,"")); }
    public boolean prepare(int stepIndex) throws Exception {
        flagsBefore(stepIndex); Step step = steps.get(stepIndex);
        Element visible = child(step.element,"visible");
        if (visible != null && !dependencies(visible)) { for(Group g:step.groups) for(Choice c:g.choices)c.selected=false; return false; }
        for (Group g:step.groups) {
            int chosen=0;
            for (Choice c:g.choices) {
                c.type = type(c);
                c.selected = c.type.equals("Required") || (c.type.equals("Recommended") && !g.rule.equals("SelectAtMostOne") && !g.rule.equals("SelectExactlyOne")) || (g.rule.equals("SelectAll") && !c.type.equals("NotUsable"));
                if(c.selected)chosen++;
            }
            if(chosen==0 && Set.of("SelectExactlyOne","SelectAtLeastOne").contains(g.rule)) {
                Choice pick = g.choices.stream().filter(c->c.type.equals("Recommended")).findFirst().orElse(null);
                if(pick==null)pick=g.choices.stream().filter(c->!c.type.equals("NotUsable")).findFirst().orElse(null);
                if(pick!=null)pick.selected=true;
            }
        }
        return true;
    }
    private String type(Choice c) throws Exception {
        Element desc=child(c.element,"typeDescriptor"), direct=child(desc,"type");
        String type;
        if(direct!=null) type=attr(direct,"name","Optional");
        else {
            Element dep=child(desc,"dependencyType"); type=attr(child(dep,"defaultType"),"name","Optional");
            for(Element pattern:children(child(dep,"patterns"),"pattern")) if(dependencies(child(pattern,"dependencies"))) {type=attr(child(pattern,"type"),"name","Optional");break;}
        }
        if(!Set.of("Required","Optional","Recommended","NotUsable","CouldBeUsable").contains(type))throw new IOException(Text.get("message_019")+type);
        return type;
    }
    public void validate(Step step) throws IOException {
        for(Group g:step.groups) {
            long n=g.choices.stream().filter(c->c.selected).count();
            for(Choice c:g.choices)if((c.type.equals("Required") && !c.selected)||(c.type.equals("NotUsable")&&c.selected))throw new IOException(Text.get("message_020")+c.name);
            if((g.rule.equals("SelectExactlyOne")&&n!=1)||(g.rule.equals("SelectAtMostOne")&&n>1)||(g.rule.equals("SelectAtLeastOne")&&n<1)||(g.rule.equals("SelectAll")&&n!=g.choices.stream().filter(c->!c.type.equals("NotUsable")).count()))throw new IOException(Text.get("message_021")+g.name);
        }
    }
    private void assignments(Element parent, List<Assignment> list, boolean selected, boolean usable) throws IOException {
        for(Element e:children(parent,null)) {
            if(!e.getTagName().equals("file")&&!e.getTagName().equals("folder"))throw new IOException(Text.get("message_022")+e.getTagName());
            if(selected || attr(e,"alwaysInstall","false").equalsIgnoreCase("true") || (usable&&attr(e,"installIfUsable","false").equalsIgnoreCase("true")))list.add(new Assignment(e));
        }
    }
    public Map<String,File> plan() throws Exception {
        List<Assignment> assignments = new ArrayList<>(); flags.clear();
        assignments(child(config,"requiredInstallFiles"),assignments,true,true);
        for(Step step:steps) {
            Element visible=child(step.element,"visible"); if(visible!=null&&!dependencies(visible))continue;
            validate(step);
            for(Group g:step.groups)for(Choice c:g.choices) { assignments(child(c.element,"files"),assignments,c.selected,!c.type.equals("NotUsable")); if(c.selected)applyFlags(c); }
        }
        for(Element pattern:children(child(child(config,"conditionalFileInstalls"),"patterns"),"pattern"))if(dependencies(child(pattern,"dependencies")))assignments(child(pattern,"files"),assignments,true,true);
        assignments.sort(Comparator.comparingInt(a->a.priority));
        Map<String,File> out=new TreeMap<>();
        for(Assignment a:assignments) {
            Element e=a.element; String source=FilesUtil.path(attr(e,"source","")).toLowerCase(Locale.ROOT), dest=FilesUtil.path(attr(e,"destination",""));
            boolean found=false;
            for(Map.Entry<String,File> entry:index.entrySet()) {
                String target=null;
                if(e.getTagName().equals("file")&&entry.getKey().equals(source))target=dest.isEmpty()?entry.getValue().getName():dest;
                if(e.getTagName().equals("folder")&&(source.isEmpty()||entry.getKey().startsWith(source+"/")))target=dest+(dest.isEmpty()?"":"/")+entry.getKey().substring(source.isEmpty()?0:source.length()+1);
                if(target!=null){out.put(FilesUtil.asset(target),entry.getValue());found=true;}
            }
            if(!found)throw new IOException(Text.get("message_023")+source);
        }
        if(out.isEmpty())throw new IOException(Text.get("message_024")); return out;
    }
}

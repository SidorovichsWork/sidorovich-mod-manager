package cr.zone.modinstaller;

import org.junit.Test;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import static org.junit.Assert.*;

public class LocalizationTest {
    private Map<String,String> read(String folder) throws Exception {
        Document document=DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(Path.of("src/main/res",folder,"strings.xml").toFile());
        Map<String,String> values=new TreeMap<>();
        NodeList nodes=document.getElementsByTagName("string");
        for(int i=0;i<nodes.getLength();i++){
            Element item=(Element)nodes.item(i);
            assertNull("Duplicate resource",values.put(item.getAttribute("name"),item.getTextContent()));
            assertFalse(item.getTextContent().isBlank());
        }
        values.remove("app_name");
        return values;
    }

    @Test public void allThreeLanguagesCoverEveryMessage() throws Exception {
        Map<String,String> english=read("values");
        assertEquals(english.keySet(),read("values-es").keySet());
        assertEquals(english.keySet(),read("values-ru").keySet());
        assertEquals(182,english.size());
        Pattern call=Pattern.compile("Text\\.get\\(\"([^\"]+)\"\\)");
        try(var files=Files.list(Path.of("src/main/java/cr/zone/modinstaller"))){
            for(Path file:files.toList()){
                Matcher matcher=call.matcher(new String(Files.readAllBytes(file),java.nio.charset.StandardCharsets.UTF_8));
                while(matcher.find()) assertTrue(file+": "+matcher.group(1),english.containsKey(matcher.group(1)));
            }
        }
    }

    @Test public void englishFallbackMatchesAndroidResources() throws Exception {
        Text.setProvider(null);
        for(var entry:read("values").entrySet()){
            if(!entry.getKey().startsWith("message_"))continue;
            String raw=entry.getValue();
            String decoded=raw.substring(1,raw.length()-1).replace("\\n","\n").replace("\\'","'").replace("\\\"","\"").replace("\\\\","\\");
            assertEquals(entry.getKey(),decoded,Text.get(entry.getKey()));
        }
        assertEquals("Ready to start",Text.get("message_175"));
    }
}

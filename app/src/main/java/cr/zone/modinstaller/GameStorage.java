package cr.zone.modinstaller;
import java.io.*;
public interface GameStorage {
    InputStream read(String path) throws Exception; // null when absent
    void write(String path, InputStream in) throws Exception;
    void delete(String path) throws Exception;
}

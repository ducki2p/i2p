package net.i2p.data;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */
 
import java.io.ByteArrayOutputStream;
import java.util.Date;
import java.util.Properties;

/**
 * Test harness for loading / storing Hash objects
 *
 * @author jrandom
 */
public class RouterAddressTest extends StructureTest {
    public DataStructure createDataStructure() throws DataFormatException {
        RouterAddress addr = new RouterAddress();
        byte data[] = new byte[32];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i%16);
        addr.setCost(42);
        addr.setExpiration(new Date(1000*60*60*24)); // jan 2 1970
        Properties options = new Properties();
        options.setProperty("hostname", "localhost");
        options.setProperty("portnum", "1234");
        addr.setOptions(options);
        addr.setTransportStyle("Blah");
        return addr; 
    }
    public DataStructure createStructureToRead() { return new RouterAddress(); }
    
    public void testBadWrite() throws Exception{
        RouterAddress addr = new RouterAddress();
        boolean error = true;
        try{
            addr.writeBytes(new ByteArrayOutputStream());
        }catch(DataFormatException dfe){
            error = true;
        }
        assertTrue(error);
    }
    
    public void testNullEquals(){
        RouterAddress addr = new RouterAddress();
        byte data[] = new byte[32];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i%16);
        addr.setCost(42);
        addr.setExpiration(new Date(1000*60*60*24)); // jan 2 1970
        Properties options = new Properties();
        options.setProperty("hostname", "localhost");
        options.setProperty("portnum", "1234");
        addr.setOptions(options);
        addr.setTransportStyle("Blah");
        assertFalse(addr.equals(null));
        assertFalse(addr.equals(""));
    }
    
    public void testToString(){
        RouterAddress addr = new RouterAddress();
        byte data[] = new byte[32];
        for (int i = 0; i < data.length; i++)
            data[i] = (byte)(i%16);
        addr.setCost(42);
        addr.setExpiration(new Date(1000*60*60*24)); // jan 2 1970
        Properties options = new Properties();
        options.setProperty("hostname", "localhost");
        options.setProperty("portnum", "1234");
        addr.setOptions(options);
        addr.setTransportStyle("Blah");
        addr.toString();
        addr.setOptions(null);
        addr.toString();
    }
}

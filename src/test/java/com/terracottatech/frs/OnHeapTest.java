/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.terracottatech.frs;

import com.terracottatech.frs.action.ActionCodec;
import com.terracottatech.frs.action.ActionCodecImpl;
import com.terracottatech.frs.action.ActionManager;
import com.terracottatech.frs.action.ActionManagerImpl;
import com.terracottatech.frs.compaction.Compactor;
import com.terracottatech.frs.compaction.CompactorImpl;
import com.terracottatech.frs.io.IOManager;
import com.terracottatech.frs.io.nio.NIOManager;
import com.terracottatech.frs.log.*;
import com.terracottatech.frs.mock.object.MockObjectManager;
import com.terracottatech.frs.object.ObjectManager;
import com.terracottatech.frs.recovery.RecoveryManager;
import com.terracottatech.frs.recovery.RecoveryManagerImpl;
import com.terracottatech.frs.transaction.TransactionActions;
import com.terracottatech.frs.transaction.TransactionManager;
import com.terracottatech.frs.transaction.TransactionManagerImpl;
import java.io.File;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

/**
 *
 * @author mscott
 */
public class OnHeapTest {

    RestartStore store;
    StagingLogManager   logMgr;
    ActionManager actionMgr;
    ObjectManager<ByteBuffer, ByteBuffer, ByteBuffer> objectMgr;
    Map<ByteBuffer, Map<ByteBuffer, ByteBuffer>> external;
        
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    public OnHeapTest() {
    }

    @BeforeClass
    public static void setUpClass() throws Exception {
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
    }

    @Before
    public void setUp() throws Exception {
        external = Collections.synchronizedMap(new HashMap<ByteBuffer, Map<ByteBuffer, ByteBuffer>>());
        ObjectManager<ByteBuffer, ByteBuffer, ByteBuffer> objectMgr = new MockObjectManager<ByteBuffer, ByteBuffer, ByteBuffer>(external);
        IOManager ioMgr = new NIOManager(folder.getRoot().getAbsolutePath(), ( 1024 * 1024));
        logMgr = new StagingLogManager(ioMgr);
        ActionCodec codec = new ActionCodecImpl(objectMgr);
        TransactionActions.registerActions(0, codec);
        MapActions.registerActions(1, codec);
        actionMgr = new ActionManagerImpl(logMgr, objectMgr, codec, new MasterLogRecordFactory());
        TransactionManager transactionMgr = new TransactionManagerImpl(actionMgr, true);
        Compactor compactor = new CompactorImpl(objectMgr, transactionMgr, logMgr);
        store = new RestartStoreImpl(objectMgr, transactionMgr, logMgr, compactor);
    }

    DecimalFormat df = new DecimalFormat("0000000000");
    
    private int addTransaction(int count, RestartStore store) throws Exception {
        String[] r = {"foo","bar","baz","boo","tim","sar","myr","chr"};
        Transaction<ByteBuffer, ByteBuffer, ByteBuffer> transaction = store.beginTransaction();
        ByteBuffer i = (ByteBuffer)ByteBuffer.allocate(4).putInt(1).flip();
        String sk = df.format(count);
        String sv = r[(int)(Math.random()*r.length)%r.length];
//        System.out.format("insert: %s=%s\n",sk,sv);
        ByteBuffer k = (ByteBuffer)ByteBuffer.allocate(1024).put(sk.getBytes()).position(1024).flip();
        ByteBuffer v = (ByteBuffer)ByteBuffer.allocate(3000).put(sv.getBytes()).position(1024).flip();
        int size = k.remaining() + v.remaining();
        transaction.put(i,k,v);
        transaction.commit();
        return size;
    }

    @Test
    public void testIt() throws Exception {
        logMgr.startup();
        int count = 0;
        long bin = 0;
        long time = System.nanoTime();
        while ( bin < 1 * 1024 * 1024 ) {
            bin += addTransaction(count++,store);  
        }
        System.out.format("%.6f sec.\n",(System.nanoTime() - time)/(1e9));
        logMgr.shutdown();
//        System.out.println(logMgr.debugInfo());
        System.out.format("bytes in: %d\n",bin);
        
        File[] list = folder.getRoot().listFiles();
        long fl = 0;
        for ( File f : list  ){
            System.out.println(f.getName() + " " + f.length());
            fl += f.length();
        }
        System.out.println("file total: " + fl);

        time = System.nanoTime();
        logMgr.startup();
        external.clear();
        RecoveryManager recoverMgr = new RecoveryManagerImpl(logMgr, actionMgr);
        recoverMgr.recover();
        System.out.format("%.6f sec.\n",(System.nanoTime() - time)/(1e9));
        long esize = 0;
        for ( Map.Entry e : external.entrySet() ) {
            esize += ((Map)e.getValue()).size();
        }
        System.out.format("recovered pulled: %d pushed: %d size: %d\n",logMgr.getRecoveryExchanger().returned(),logMgr.getRecoveryExchanger().count(),
                    esize);
        
//        for ( Map.Entry<ByteBuffer, Map<ByteBuffer,ByteBuffer>> ids : external.entrySet() ) {
//            int id = ids.getKey().getInt(0);
//            Map<ByteBuffer, ByteBuffer> map = ids.getValue();
//            for ( Map.Entry<ByteBuffer,ByteBuffer> entry : map.entrySet() ) {
//                byte[] g = new byte[entry.getKey().remaining()];
//                entry.getKey().mark();
//                entry.getKey().get(g);
//                String skey = new String(g);
//                entry.getKey().reset();
//                ByteBuffer val = entry.getValue();
//                g = new byte[val.remaining()];
//                val.get(g);
//                String sval = new String(g);
//                System.out.println(id + " " + skey + " " + sval);
//            }
//        }
//        
        
        System.out.println("=========");
        
        for (int x=0;x<100;x++) addTransaction(count + x, store);           
        logMgr.shutdown();
        
        external.clear();
        logMgr.startup();
        recoverMgr = new RecoveryManagerImpl(logMgr, actionMgr);
        recoverMgr.recover();
        esize = 0;
        for ( Map.Entry e : external.entrySet() ) {
            esize += ((Map)e.getValue()).size();
        }
        System.out.format("recovered pulled: %d pushed: %d size: %d\n",logMgr.getRecoveryExchanger().returned(),logMgr.getRecoveryExchanger().count(),
                    esize);
        
//        for ( Map.Entry<ByteBuffer, Map<ByteBuffer,ByteBuffer>> ids : external.entrySet() ) {
//            int id = ids.getKey().getInt(0);
//            Map<ByteBuffer, ByteBuffer> map = ids.getValue();
//            for ( ByteBuffer key : map.keySet() ) {
//                byte[] g = new byte[key.remaining()];
//                key.mark();
//                key.get(g);
//                String skey = new String(g);
//                key.reset();
//                ByteBuffer val = map.get(key);
//                g = new byte[val.remaining()];
//                val.get(g);
//                String sval = new String(g);
//                System.out.println(id + " " + skey + " " + sval);
//            }
//            System.out.format("size: %d\n",map.size());
//        }
        logMgr.shutdown();
//        System.out.println(logMgr.debugInfo());
    }

    @After
    public void tearDown() {
    }
    // TODO add test methods here.
    // The methods must be annotated with annotation @Test. For example:
    //
    // @Test
    // public void hello() {}
}

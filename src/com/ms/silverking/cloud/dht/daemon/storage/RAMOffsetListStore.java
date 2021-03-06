package com.ms.silverking.cloud.dht.daemon.storage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import com.ms.silverking.cloud.dht.NamespaceOptions;
import com.ms.silverking.cloud.dht.RevisionMode;
import com.ms.silverking.io.util.BufferUtil;
import com.ms.silverking.numeric.NumConversion;
import com.ms.silverking.text.StringUtil;

/**
 * OffsetListStore in RAM 
 */
public class RAMOffsetListStore implements OffsetListStore {
    private final List<OffsetList>  lists;
    private final boolean           supportsStorageTime;
        
    /*
     * Indexing to lists is 0-based internally and 1-based externally.
     * Methods contain logic to convert.
     * Each list is stamped with its external index.
     * 
     * (The 1-based external indexing is required to enable 
     * disambiguation with respect to the non-offset list
     * entries which may have a value of zero. I.e. anything
     * <= -1 is known to be an offset list.  
     */
    
    public RAMOffsetListStore(NamespaceOptions nsOptions) {
        lists = new ArrayList<>();
        supportsStorageTime = nsOptions.getRevisionMode() == RevisionMode.UNRESTRICTED_REVISIONS;
    }

    @Override
    public OffsetList newOffsetList() {
        OffsetList  list;
        
        list = new RAMOffsetList(lists.size() + 1, supportsStorageTime);
        lists.add(list);
        return list;
    }

    @Override
    public OffsetList getOffsetList(int index) {
        return lists.get(index - 1);
    }
    
    public int getNumLists() {
        return lists.size();
    }
    
    private int headerSizeBytes() {
        // number of lists + the size of each list, stored as ints
        return (1 + lists.size()) * NumConversion.BYTES_PER_INT;
    }
    
    public int persistedSizeBytes() {
        int size;
        
        // make room for the size integer,
        // and an offset integer for each list
        size = headerSizeBytes();
        for (OffsetList list : lists) {
            size += ((RAMOffsetList)list).persistedSizeBytes();
        }
        return size;
    }
    
    /*
     * Persisted format:
     *      Header
     *      Persisted lists...
     *      
     * Header:
     *      lists size      4 bytes
     *      list offset...  4 bytes * lists.size()
     */

    public void persist(ByteBuffer buf) {
        int listOffset;
        
        buf = buf.order(ByteOrder.nativeOrder());
        if (debug) {
            System.out.println("RAMOffsetListStore.persist");
            System.out.println(lists.size());
        }
        buf.putInt(lists.size());
        listOffset = headerSizeBytes();
        for (OffsetList list : lists) {
            // store each list's offset
            if (debug) {
                System.out.println(listOffset);
            }
            buf.putInt(listOffset);
            listOffset += ((RAMOffsetList)list).persistedSizeBytes();
        }
        if (debug) {
            System.out.println("persist lists");
        }
        for (OffsetList list : lists) {
            ((RAMOffsetList)list).persist(buf);
        }
        //System.out.printf("%s\n", BufferUtil.duplicate(buf).rewind());
        //System.out.printf("%s\n", StringUtil.byteBufferToHexString((ByteBuffer)BufferUtil.duplicate(buf).rewind()));        
    }
}

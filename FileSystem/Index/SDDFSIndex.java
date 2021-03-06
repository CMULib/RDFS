package FileSystem.Index;

import FileSystem.Base.SDDFSFile;
import FileSystem.Base.SDDFSNode;
import FileSystem.Base.SDFileChunk;
import FileSystem.Master.SDDFSChunkTransfer;
import FileSystem.Util.SDDFSConstants;
import Logging.SDLogOperation;
import Logging.SDLogger;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.*;

/**
 * Created by amaliujia on 14-12-28.
 */
public class SDDFSIndex {
//    private static Logger logger = LoggerFactory.getLogger(SDDFSIndex.class);

    private String lock;

    // file to data nodes
    private Map<String, SDDFSNode> dataNodes;

    //file to file id
    private Map<String, Long> fileIndex;

    //file id to file instances
    private Map<Long, SDDFSFile> files;

    //chunk id to chunk instances
    private Map<Long, SDFileChunk> chunks;

    //operation log
    private SDLogger sdLogger;

    public SDDFSIndex(SDLogger sdLogger){
        this.lock = "SkyDragon";
        this.sdLogger = sdLogger;
        this.dataNodes = new HashMap<String, SDDFSNode>();
        this.fileIndex = new HashMap<String, Long>();
        this.chunks = new HashMap<Long, SDFileChunk>();
        this.files = new HashMap<Long, SDDFSFile>();
    }

    /**
     * Call by heartbeats function to update data nodes.
     * @param serviceName
     *                  the service name of data node.
     * @param registryHost
     *                  registry host of data node.
     * @param registryPort
     *                   registry port of data node.
     * @param numChunker
     *                   number of chunker of data node.
     * @param timestamp
     *                   time stamp of this operation.
     * @param logable
     *                  if need to log.
     */
    public void updateDataNode(String serviceName, String registryHost, int registryPort,
                               int numChunker, long timestamp, boolean logable){
        SDDFSNode dataNode = null;
        synchronized (lock){
            if(dataNodes.containsKey(serviceName)){
                dataNode = dataNodes.get(serviceName);
                //TODO: how to log it?

            } else{
                 dataNode = new SDDFSNode(serviceName, registryHost, registryPort);
                 dataNodes.put(serviceName, dataNode);
                 if(logable){
                    dfsLog(SDLogOperation.UPDATE_DATA_NODE, new Object[] {serviceName,
                            registryHost, registryPort, numChunker}) ;
                }
            }
            dataNode.setChunkNumber(numChunker);
            dataNode.setTimestamp(timestamp);
            System.out.println(dataNode.toString());
        }
    }

    /**
     * Remove data node from index if lose connection of data node.
     * @param serviceName
     *                  service name of data node.
     * @param logable
     *                  if need to log.
     */
    public void removeDataNode(String serviceName, boolean logable){
        synchronized (lock){
            this.dataNodes.remove(serviceName);
            if(logable){
                dfsLog(SDLogOperation.REMOVE_DATA_NODE, new Object[] {serviceName});
            }
        }
    }

    /**
     * Create file in distributed file system.
     * @param fileName
     *                  file name of data node.
     * @param replication
     *                  number of replication.
     * @param logable
     *                  if need to log.
     * @return
     *                  Reference of SDDFSFile.
     */
    public SDDFSFile createFile(String fileName, int replication, boolean logable){
        System.err.println("get in index rmi service");
        SDDFSFile file = null;

        RandomAccessFile randomAccessFile = null;
        try {
            randomAccessFile = new RandomAccessFile(fileName, "rw");
        } catch (FileNotFoundException e) {
            System.err.println(fileName + " doesn't exist" );
            return file;
        }

        synchronized (lock){
            if(logable){
                dfsLog(SDLogOperation.DFS_CREATE_FILE, new Object[] {fileName, replication});
            }
            if(fileIndex.containsKey(fileName)){
                deleteFile(fileName, true);
            }
            file = new SDDFSFile(SDDFSFile.maxId.incrementAndGet(), fileName);
            fileIndex.put(fileName, file.getFileID());
            files.put(file.getFileID(), file);
        }

        long filesize = -1;
        try {
             filesize = randomAccessFile.length();
             //filesize = 0x4000000 * 2;
            //TODO:test here
            int chunknumToSplit = (int)(filesize / SDDFSConstants.CHUNK_SIZE);
            long lastOff = 0;
            if(filesize % SDDFSConstants.CHUNK_SIZE != 0){
               lastOff = chunknumToSplit * SDDFSConstants.CHUNK_SIZE;
            }
            for(int i = 0; i < chunknumToSplit ; i++){
                createChunk(file.getFileID(), i * SDDFSConstants.CHUNK_SIZE, (int)SDDFSConstants.CHUNK_SIZE, true);
            }
            if(lastOff > 0){
                createChunk(file.getFileID(), lastOff, filesize - chunknumToSplit * SDDFSConstants.CHUNK_SIZE, true);
            }
        } catch (IOException e) {
            System.err.println(fileName + " cannot be modified     filesize " + filesize);
            return file;
        }

        if(logable){
            dfsLog(SDLogOperation.DFS_CREATE_FILE, new Object[] {fileName, replication});
        }

        return file;
    }

    /**
     * Get reference of file in DFS.
     * @param
     *          fileName filename of data node.
     * @return
     *          Reference of SDDFSFile.
     */
    public SDDFSFile getFile(String fileName){
        SDDFSFile file = null;
        synchronized (lock){
           if(fileIndex.containsKey(fileName)){
               file = files.get(fileIndex.get(fileName));
           }
        }
        return file;
    }

    /**
     * List all files in DFS.
     * @return
     *          Array of SDDFSFiles.
     */
    public SDDFSFile[] listFiles(){
        SDDFSFile[] fs = null;

        synchronized (lock){
            fs = new SDDFSFile[files.size()];
            files.values().toArray(fs);
        }
        return fs;
    }

    /**
     * Delete file from DFS.
     * @param serviceName
     *              service name of data node.
     * @param logable
     *              if need to log.
     */
    public void deleteFile(String serviceName, boolean logable){
        synchronized (lock){
            if(logable){
                dfsLog(SDLogOperation.DFS_DELETE_FILE, new Object[] {serviceName});
            }
            if(fileIndex.containsKey(serviceName)){
                long id = fileIndex.get(serviceName);
                fileIndex.remove(serviceName);
                files.remove(id);
            }
        }
    }

    /**
     * Create Chunk of a file.
     * @param fileId
     *               file ID.
     * @param offset
     *              offset in given file.
     * @param size
     *              chunk size.
     * @param logable
     *              if need to log.
     * @return
     *              Reference of SDFileChunk.
     */
    private SDFileChunk createChunk(long fileId, long offset, long size, boolean logable){
        SDFileChunk chunk = null;
        synchronized (lock){
            if(logable){
                dfsLog(SDLogOperation.DFS_CREATE_CHUNK, new Object[]{fileId, offset, size});
            }
            if(files.containsKey(fileId)){
                SDDFSFile file = files.get(fileId);
                SDDFSNode[] dataNodes = allocateNode(file.getReplication());
                long chunkId = SDFileChunk.maxId.incrementAndGet();
                chunk = new SDFileChunk(chunkId, fileId, offset, size, dataNodes);
                file.addChunk(chunk);
                chunks.put(chunk.getId(), chunk);
            }
        }
        return chunk;
    }

    /**
     * Log function to print
     * @param operationType
     *                  Operation type code.
     * @param arguments
     *                  Operation arguments.
     */
    private void dfsLog(byte operationType, Object[] arguments){
        SDLogOperation operation = new SDLogOperation(operationType, arguments);
        sdLogger.writeLog(operation);
    }

    /**
     * Distribute files into data nodes.
     * @param fileID
     *              file to be distributed.
     * @param logable
     *              if need to log.
     */
    public void distributeFile(long fileID, boolean logable){
        synchronized (lock){
            if(!files.containsKey(fileID)){
                System.err.println(fileID + " doesn't exist. Distribution failed !!");
                return;
            }

            SDDFSChunkTransfer chunkTransfer = new SDDFSChunkTransfer(files.get(fileID));
            chunkTransfer.distributeFile();
            if(logable){
                dfsLog(SDLogOperation.DFS_DISTRIBUTE_FILE, new Object[] {fileID});
            }
        }
    }

    /**
     * Allocate data nodes for a file which to be distributed.
     * @param replication
     *                  The replication number for given file.
     * @return
     *                  SDDFSNode array.
     */
    private SDDFSNode[] allocateNode(int replication) {
        if(replication != 1){
            System.err.println("replication is not 1 !!!");
        }
        List<SDDFSNode> nodes = new ArrayList<SDDFSNode>();
        SDDFSNode[] results = null;
        SDDFSNode[] allNodes = new SDDFSNode[dataNodes.size()];
        dataNodes.values().toArray(allNodes);
        Arrays.sort(allNodes, new Comparator<SDDFSNode>() {
            @Override
            public int compare(SDDFSNode o1, SDDFSNode o2) {
                return o1.getChunkNumber() - o2.getChunkNumber();
            }
        });
        for(int i = 0; i < replication && i < allNodes.length; i++){
            nodes.add(allNodes[i]);
        }
        results = new SDDFSNode[nodes.size()];
        nodes.toArray(results);
        return results;
    }

    /**
     * Get file id from index.
     * @param filename
     *              filename of data node.
     * @return
     *              file ID.
     */
    public long getFileID(String filename){
        synchronized (lock){
            if(fileIndex.containsKey(filename)){
                return fileIndex.get(filename);
            }else{
                return -1;
            }
        }
    }

    //TODO: log recover
}

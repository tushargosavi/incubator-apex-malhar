package com.datatorrent.lib.io.fs;

import com.datatorrent.api.DefaultPartition;
import com.esotericsoftware.kryo.Kryo;
import com.google.common.collect.Lists;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Input operator that reads files from a directory.
 * <p/>
 * Provides the same functionality as the AbstractFSDirectoryInputOperator
 * except that this utilized dynamic partitioning where the user can set the
 * preferred number of pending files per operator as well as the max number of
 * operators and define a repartition interval. When the repartition interval
 * passes then a new number of operators are created to accommodate the
 * remaining pending files.
 * <p/>
 * 
 * @since 1.0.4
 */
public abstract class AbstractThroughputHashFSDirectoryInputOperator<T> extends AbstractFSDirectoryInputOperator<T>
{
  private static final Logger LOG = LoggerFactory.getLogger(AbstractThroughputHashFSDirectoryInputOperator.class);
  
  private long repartitionInterval = 60L * 1000L;
  private int preferredMaxPendingFilesPerOperator = 10;
  
  private boolean firstStart=true;
  private transient long lastRepartition = 0;
  
  public void setRepartitionInterval(long repartitionInterval)
  {
    this.repartitionInterval = repartitionInterval;
  }

  public long getRepartitionInterval()
  {
    return repartitionInterval;
  }

  public void setPreferredMaxPendingFilesPerOperator(int pendingFilesPerOperator)
  {
    this.preferredMaxPendingFilesPerOperator = pendingFilesPerOperator;
  }

  public int getPreferredMaxPendingFilesPerOperator()
  {
    return preferredMaxPendingFilesPerOperator;
  }
  
  @Override
  public Collection<Partition<AbstractFSDirectoryInputOperator<T>>> definePartitions(Collection<Partition<AbstractFSDirectoryInputOperator<T>>> partitions, int incrementalCapacity)
  {
    lastRepartition = System.currentTimeMillis();
    //
    // Build collective state from all instances of the operator.
    //
    
    Set<String> totalProcessedFiles = new HashSet<String>();
    List<FailedFile> currentFiles = new LinkedList<FailedFile>();
    List<DirectoryScanner> oldscanners = new LinkedList<DirectoryScanner>();
    List<FailedFile> totalFailedFiles = new LinkedList<FailedFile>();
    List<Path> totalPendingFiles = new LinkedList<Path>();
    for(Partition<AbstractFSDirectoryInputOperator<T>> partition : partitions) {
      AbstractFSDirectoryInputOperator<T> oper = partition.getPartitionedInstance();
      totalProcessedFiles.addAll(oper.processedFiles);
      totalFailedFiles.addAll(oper.failedFiles);
      totalPendingFiles.addAll(oper.pendingFiles);
      oper.pendingFiles.size();
      if (oper.currentFile != null) {
        currentFiles.add(new FailedFile(oper.currentFile, oper.offset));
      }
      oldscanners.add(oper.getScanner());  
    }
    
    int totalFileCount;
    int newOperatorCount;
    
    if(!firstStart) {
      totalFileCount = currentFiles.size() + totalFailedFiles.size() + totalPendingFiles.size();
      LOG.debug("definePartitions: Total File Count: {}", totalFileCount);
      newOperatorCount = totalFileCount / preferredMaxPendingFilesPerOperator;
    
      if(totalFileCount % preferredMaxPendingFilesPerOperator > 0) {
        newOperatorCount++;
      }
    
      if(newOperatorCount > partitionCount) {
        newOperatorCount = partitionCount;
      }
    }
    else {
      newOperatorCount = partitionCount;
      firstStart = false;
    }
    
    //if(newOperatorCount == partitions.size())
    //{
    //  return partitions;
    //}
    
    Kryo kryo = new Kryo();
    
    if(newOperatorCount == 0)
    {
      Collection<Partition<AbstractFSDirectoryInputOperator<T>>> newPartitions = Lists.newArrayListWithExpectedSize(1);

      AbstractThroughputHashFSDirectoryInputOperator<T> operator;
      
      operator = kryo.copy(this);
  
      operator.processedFiles.addAll(totalProcessedFiles);
      operator.currentFile = null;
      operator.offset = 0;
      operator.pendingFiles.clear();
      operator.pendingFiles.addAll(totalPendingFiles);
      operator.failedFiles.clear();
      operator.failedFiles.addAll(totalFailedFiles);
      operator.unfinishedFiles.clear();
      operator.unfinishedFiles.addAll(currentFiles);
      
      List<DirectoryScanner> scanners = scanner.partition(1, oldscanners);
      
      operator.setScanner(scanners.get(0));
      
      newPartitions.add(new DefaultPartition<AbstractFSDirectoryInputOperator<T>>(operator));
      
      return newPartitions;
    }
    
    
    //
    // Create partitions of scanners, scanner's partition method will do state
    // transfer for DirectoryScanner objects.
    //
    List<DirectoryScanner> scanners = scanner.partition(newOperatorCount, oldscanners);

    Collection<Partition<AbstractFSDirectoryInputOperator<T>>> newPartitions = Lists.newArrayListWithExpectedSize(newOperatorCount);
    for (int i=0; i<scanners.size(); i++) {
      AbstractThroughputHashFSDirectoryInputOperator<T> oper = kryo.copy(this);
      DirectoryScanner scn = scanners.get(i);
      oper.setScanner(scn);

      // Do state transfer for processed files.
      oper.processedFiles.addAll(totalProcessedFiles);

      /* redistribute unfinished files properly */
      oper.unfinishedFiles.clear();
      oper.currentFile = null;
      oper.offset = 0;
      Iterator<FailedFile> unfinishedIter = currentFiles.iterator();
      while(unfinishedIter.hasNext()) {
        FailedFile unfinishedFile = unfinishedIter.next();
        if (scn.acceptFile(unfinishedFile.path)) {
          oper.unfinishedFiles.add(unfinishedFile);
          unfinishedIter.remove();
        }
      }

      // transfer failed files 
      oper.failedFiles.clear();
      Iterator<FailedFile> iter = totalFailedFiles.iterator();
      while (iter.hasNext()) {
        FailedFile ff = iter.next();
        if (scn.acceptFile(ff.path)) {
          oper.failedFiles.add(ff);
          iter.remove();
        }
      }
      
      /* redistribute pending files properly */
      oper.pendingFiles.clear();
      Iterator<Path> pendingFilesIterator = totalPendingFiles.iterator();
      while(pendingFilesIterator.hasNext()) {
        Path path = pendingFilesIterator.next();
        if(scn.acceptFile(path.toString())) {
          oper.pendingFiles.add(path);
          pendingFilesIterator.remove();
        }
      }

      newPartitions.add(new DefaultPartition<AbstractFSDirectoryInputOperator<T>>(oper));
    }

    LOG.info("definePartitions called returning {} partitions", newPartitions.size());
    
    return newPartitions;
  }
  
  @Override
  public void partitioned(Map<Integer, Partition<AbstractFSDirectoryInputOperator<T>>> partitions)
  {
  }
  
  @Override
  public Response processStats(BatchedOperatorStats batchedOperatorStats)
  {
    Response response = new Response();
    response.repartitionRequired = false;
    
    if(System.currentTimeMillis() - repartitionInterval > lastRepartition) {
      response.repartitionRequired = true;
      return response;
    }

    return response;
  }
}
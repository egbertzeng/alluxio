/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.master.keyvalue;

import alluxio.AlluxioURI;
import alluxio.Constants;
import alluxio.clock.SystemClock;
import alluxio.exception.AccessControlException;
import alluxio.exception.AlluxioException;
import alluxio.exception.ExceptionMessage;
import alluxio.exception.FileAlreadyExistsException;
import alluxio.exception.FileDoesNotExistException;
import alluxio.exception.InvalidPathException;
import alluxio.master.AbstractMaster;
import alluxio.master.MasterRegistry;
import alluxio.master.file.FileSystemMaster;
import alluxio.master.file.options.CreateDirectoryOptions;
import alluxio.master.file.options.DeleteOptions;
import alluxio.master.file.options.RenameOptions;
import alluxio.master.journal.Journal;
import alluxio.proto.journal.Journal.JournalEntry;
import alluxio.proto.journal.KeyValue.CompletePartitionEntry;
import alluxio.proto.journal.KeyValue.CompleteStoreEntry;
import alluxio.proto.journal.KeyValue.CreateStoreEntry;
import alluxio.proto.journal.KeyValue.DeleteStoreEntry;
import alluxio.proto.journal.KeyValue.MergeStoreEntry;
import alluxio.proto.journal.KeyValue.RenameStoreEntry;
import alluxio.thrift.KeyValueMasterClientService;
import alluxio.thrift.PartitionInfo;
import alluxio.util.IdUtils;
import alluxio.util.executor.ExecutorServiceFactories;
import alluxio.util.io.PathUtils;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import org.apache.thrift.TProcessor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

import javax.annotation.concurrent.ThreadSafe;

/**
 * The key-value master stores key-value store information in Alluxio, including the partitions of
 * each key-value store.
 */
@ThreadSafe
public final class KeyValueMaster extends AbstractMaster {
  private static final Set<Class<?>> DEPS = ImmutableSet.<Class<?>>of(FileSystemMaster.class);

  private final FileSystemMaster mFileSystemMaster;

  /** Map from file id of a complete store to the list of partitions in this store. */
  private final Map<Long, List<PartitionInfo>> mCompleteStoreToPartitions;
  /**
   * Map from file id of an incomplete store (i.e., some one is still writing new partitions) to the
   * list of partitions in this store.
   */
  private final Map<Long, List<PartitionInfo>> mIncompleteStoreToPartitions;

  /**
   * @param registry the master registry
   * @param journal a {@link Journal} to write journal entries to
   */
  public KeyValueMaster(MasterRegistry registry, Journal journal) {
    super(journal, new SystemClock(), ExecutorServiceFactories
        .fixedThreadPoolExecutorServiceFactory(Constants.KEY_VALUE_MASTER_NAME, 2));
    mFileSystemMaster = registry.get(FileSystemMaster.class);
    mCompleteStoreToPartitions = new HashMap<>();
    mIncompleteStoreToPartitions = new HashMap<>();
    registry.add(KeyValueMaster.class, this);
  }

  @Override
  public Map<String, TProcessor> getServices() {
    Map<String, TProcessor> services = new HashMap<>();
    services.put(Constants.KEY_VALUE_MASTER_CLIENT_SERVICE_NAME,
        new KeyValueMasterClientService.Processor<>(new KeyValueMasterClientServiceHandler(this)));
    return services;
  }

  @Override
  public String getName() {
    return Constants.KEY_VALUE_MASTER_NAME;
  }

  @Override
  public Set<Class<?>> getDependencies() {
    return DEPS;
  }

  @Override
  public synchronized void processJournalEntry(JournalEntry entry) throws IOException {
    try {
      if (entry.hasCreateStore()) {
        createStoreFromEntry(entry.getCreateStore());
      } else if (entry.hasCompletePartition()) {
        completePartitionFromEntry(entry.getCompletePartition());
      } else if (entry.hasCompleteStore()) {
        completeStoreFromEntry(entry.getCompleteStore());
      } else if (entry.hasDeleteStore()) {
        deleteStoreFromEntry(entry.getDeleteStore());
      } else if (entry.hasRenameStore()) {
        renameStoreFromEntry(entry.getRenameStore());
      } else if (entry.hasMergeStore()) {
        mergeStoreFromEntry(entry.getMergeStore());
      } else {
        throw new IOException(ExceptionMessage.UNEXPECTED_JOURNAL_ENTRY.getMessage(entry));
      }
    } catch (AlluxioException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public synchronized Iterator<JournalEntry> iterator() {
    return Iterators.concat(getJournalEntryIterator(mCompleteStoreToPartitions),
        getJournalEntryIterator(mIncompleteStoreToPartitions));
  }

  @Override
  public void start(boolean isLeader) throws IOException {
    super.start(isLeader);
  }

  /**
   * Marks a partition complete and adds it to an incomplete key-value store.
   *
   * @param path URI of the key-value store
   * @param info information of this completed partition
   * @throws AccessControlException if permission checking fails
   * @throws FileDoesNotExistException if the key-value store URI does not exists
   * @throws InvalidPathException if the path is invalid
   */
  public synchronized void completePartition(AlluxioURI path, PartitionInfo info)
      throws AccessControlException, FileDoesNotExistException, InvalidPathException {
    final long fileId = mFileSystemMaster.getFileId(path);
    if (fileId == IdUtils.INVALID_FILE_ID) {
      throw new FileDoesNotExistException(
          String.format("Failed to completePartition: path %s does not exist", path));
    }

    completePartitionInternal(fileId, info);

    writeJournalEntry(newCompletePartitionEntry(fileId, info));
    flushJournal();
  }

  // Marks a partition complete, called when replaying journals
  private void completePartitionFromEntry(CompletePartitionEntry entry)
      throws FileDoesNotExistException {
    PartitionInfo info = new PartitionInfo(entry.getKeyStartBytes().asReadOnlyByteBuffer(),
        entry.getKeyLimitBytes().asReadOnlyByteBuffer(), entry.getBlockId(), entry.getKeyCount());
    completePartitionInternal(entry.getStoreId(), info);
  }

  // Internal implementation to mark a partition complete
  private void completePartitionInternal(long fileId, PartitionInfo info)
      throws FileDoesNotExistException {
    if (!mIncompleteStoreToPartitions.containsKey(fileId)) {
      // TODO(binfan): throw a better exception
      throw new FileDoesNotExistException(String.format(
          "Failed to completeStore: KeyValueStore (fileId=%d) was not created before", fileId));
    }
    // NOTE: deep copy the partition info object
    mIncompleteStoreToPartitions.get(fileId).add(new PartitionInfo(info));
  }

  /**
   * Marks a key-value store complete.
   *
   * @param path URI of the key-value store
   * @throws FileDoesNotExistException if the key-value store URI does not exists
   * @throws InvalidPathException if the path is not valid
   * @throws AccessControlException if permission checking fails
   */
  public synchronized void completeStore(AlluxioURI path)
      throws FileDoesNotExistException, InvalidPathException, AccessControlException {
    final long fileId = mFileSystemMaster.getFileId(path);
    if (fileId == IdUtils.INVALID_FILE_ID) {
      throw new FileDoesNotExistException(
          String.format("Failed to completeStore: path %s does not exist", path));
    }
    completeStoreInternal(fileId);
    writeJournalEntry(newCompleteStoreEntry(fileId));
    flushJournal();
  }

  // Marks a store complete, called when replaying journals
  private void completeStoreFromEntry(CompleteStoreEntry entry) throws FileDoesNotExistException {
    completeStoreInternal(entry.getStoreId());
  }

  // Internal implementation to mark a store complete
  private void completeStoreInternal(long fileId) throws FileDoesNotExistException {
    if (!mIncompleteStoreToPartitions.containsKey(fileId)) {
      // TODO(binfan): throw a better exception
      throw new FileDoesNotExistException(String.format(
          "Failed to completeStore: KeyValueStore (fileId=%d) was not created before", fileId));
    }
    List<PartitionInfo> partitions = mIncompleteStoreToPartitions.remove(fileId);
    mCompleteStoreToPartitions.put(fileId, partitions);
  }

  /**
   * Creates a new key-value store.
   *
   * @param path URI of the key-value store
   * @throws FileAlreadyExistsException if a key-value store URI exists
   * @throws InvalidPathException if the given path is invalid
   * @throws AccessControlException if permission checking fails
   */
  public synchronized void createStore(AlluxioURI path)
      throws FileAlreadyExistsException, InvalidPathException, AccessControlException {
    try {
      // Create this dir
      mFileSystemMaster
          .createDirectory(path, CreateDirectoryOptions.defaults().setRecursive(true));
    } catch (IOException e) {
      // TODO(binfan): Investigate why {@link FileSystemMaster#createDirectory} throws IOException
      throw new InvalidPathException(
          String.format("Failed to createStore: can not create path %s", path), e);
    } catch (FileDoesNotExistException e) {
      // This should be impossible since we pass the recursive option into mkdir
      throw Throwables.propagate(e);
    }
    long fileId = mFileSystemMaster.getFileId(path);
    Preconditions.checkState(fileId != IdUtils.INVALID_FILE_ID);

    createStoreInternal(fileId);
    writeJournalEntry(newCreateStoreEntry(fileId));
    flushJournal();
  }

  // Creates a store, called when replaying journals
  private void createStoreFromEntry(CreateStoreEntry entry) throws FileAlreadyExistsException {
    createStoreInternal(entry.getStoreId());
  }

  // Internal implementation to create a store
  private void createStoreInternal(long fileId) throws FileAlreadyExistsException {
    if (mIncompleteStoreToPartitions.containsKey(fileId)) {
      // TODO(binfan): throw a better exception
      throw new FileAlreadyExistsException(String
          .format("Failed to createStore: KeyValueStore (fileId=%d) is already created", fileId));
    }
    mIncompleteStoreToPartitions.put(fileId, new ArrayList<PartitionInfo>());
  }

  /**
   * Deletes a completed key-value store.
   *
   * @param uri {@link AlluxioURI} to the store
   * @throws IOException if non-Alluxio error occurs
   * @throws InvalidPathException if the uri exists but is not a key-value store
   * @throws FileDoesNotExistException if the uri does not exist
   * @throws AlluxioException if other Alluxio error occurs
   */
  public synchronized void deleteStore(AlluxioURI uri)
      throws IOException, InvalidPathException, FileDoesNotExistException, AlluxioException {
    long fileId = getFileId(uri);
    checkIsCompletePartition(fileId, uri);
    mFileSystemMaster.delete(uri, DeleteOptions.defaults().setRecursive(true));
    deleteStoreInternal(fileId);
    writeJournalEntry(newDeleteStoreEntry(fileId));
    flushJournal();
  }

  // Deletes a store, called when replaying journals.
  private void deleteStoreFromEntry(DeleteStoreEntry entry) {
    deleteStoreInternal(entry.getStoreId());
  }

  // Internal implementation to deleteStore a key-value store.
  private void deleteStoreInternal(long fileId) {
    mCompleteStoreToPartitions.remove(fileId);
  }

  private long getFileId(AlluxioURI uri)
      throws AccessControlException, FileDoesNotExistException, InvalidPathException {
    long fileId = mFileSystemMaster.getFileId(uri);
    if (fileId == IdUtils.INVALID_FILE_ID) {
      throw new FileDoesNotExistException(ExceptionMessage.PATH_DOES_NOT_EXIST.getMessage(uri));
    }
    return fileId;
  }

  void checkIsCompletePartition(long fileId, AlluxioURI uri) throws InvalidPathException {
    if (!mCompleteStoreToPartitions.containsKey(fileId)) {
      throw new InvalidPathException(ExceptionMessage.INVALID_KEY_VALUE_STORE_URI.getMessage(uri));
    }
  }

  /**
   * Renames one completed key-value store.
   *
   * @param oldUri the old {@link AlluxioURI} to the store
   * @param newUri the {@link AlluxioURI} to the store
   * @throws IOException if non-Alluxio error occurs
   * @throws AlluxioException if other Alluxio error occurs
   */
  public synchronized void renameStore(AlluxioURI oldUri, AlluxioURI newUri)
      throws IOException, AlluxioException {
    long oldFileId = getFileId(oldUri);
    checkIsCompletePartition(oldFileId, oldUri);
    try {
      mFileSystemMaster.rename(oldUri, newUri, RenameOptions.defaults());
    } catch (FileAlreadyExistsException e) {
      throw new FileAlreadyExistsException(
          String.format("failed to rename store:the path %s has been used", newUri), e);
    }

    final long newFileId = mFileSystemMaster.getFileId(newUri);
    Preconditions.checkState(newFileId != IdUtils.INVALID_FILE_ID);
    renameStoreInternal(oldFileId, newFileId);

    writeJournalEntry(newRenameStoreEntry(oldFileId, newFileId));
    flushJournal();
  }

  private void renameStoreInternal(long oldFileId, long newFileId) {
    List<PartitionInfo> partitionsRenamed = mCompleteStoreToPartitions.remove(oldFileId);
    mCompleteStoreToPartitions.put(newFileId, partitionsRenamed);
  }

  // Rename one completed stores, called when replaying journals.
  private void renameStoreFromEntry(RenameStoreEntry entry) {
    renameStoreInternal(entry.getOldStoreId(), entry.getNewStoreId());
  }

  /**
   * Merges one completed key-value store to another completed key-value store.
   *
   * @param fromUri the {@link AlluxioURI} to the store to be merged
   * @param toUri the {@link AlluxioURI} to the store to be merged to
   * @throws IOException if non-Alluxio error occurs
   * @throws InvalidPathException if the uri exists but is not a key-value store
   * @throws FileDoesNotExistException if the uri does not exist
   * @throws AlluxioException if other Alluxio error occurs
   */
  public synchronized void mergeStore(AlluxioURI fromUri, AlluxioURI toUri)
      throws IOException, FileDoesNotExistException, InvalidPathException, AlluxioException {
    long fromFileId = getFileId(fromUri);
    long toFileId = getFileId(toUri);
    checkIsCompletePartition(fromFileId, fromUri);
    checkIsCompletePartition(toFileId, toUri);

    // Rename fromUri to "toUri/%s-%s" % (last component of fromUri, UUID).
    // NOTE: rename does not change the existing block IDs.
    mFileSystemMaster.rename(fromUri, new AlluxioURI(PathUtils.concatPath(toUri.toString(),
        String.format("%s-%s", fromUri.getName(), UUID.randomUUID().toString()))),
        RenameOptions.defaults());
    mergeStoreInternal(fromFileId, toFileId);

    writeJournalEntry(newMergeStoreEntry(fromFileId, toFileId));
    flushJournal();
  }

  // Internal implementation to merge two completed stores.
  private void mergeStoreInternal(long fromFileId, long toFileId) {
    // Move partition infos to the new store.
    List<PartitionInfo> partitionsToBeMerged = mCompleteStoreToPartitions.remove(fromFileId);
    mCompleteStoreToPartitions.get(toFileId).addAll(partitionsToBeMerged);
  }

  // Merges two completed stores, called when replaying journals.
  private void mergeStoreFromEntry(MergeStoreEntry entry) {
    mergeStoreInternal(entry.getFromStoreId(), entry.getToStoreId());
  }

  /**
   * Gets a list of partitions of a given key-value store.
   *
   * @param path URI of the key-value store
   * @return a list of partition information
   * @throws FileDoesNotExistException if the key-value store URI does not exists
   * @throws AccessControlException if permission checking fails
   * @throws InvalidPathException if the path is invalid
   */
  public synchronized List<PartitionInfo> getPartitionInfo(AlluxioURI path)
      throws FileDoesNotExistException, AccessControlException, InvalidPathException {
    long fileId = getFileId(path);
    List<PartitionInfo> partitions = mCompleteStoreToPartitions.get(fileId);
    if (partitions == null) {
      return new ArrayList<>();
    }
    return partitions;
  }

  private JournalEntry newCreateStoreEntry(long fileId) {
    CreateStoreEntry createStore = CreateStoreEntry.newBuilder().setStoreId(fileId).build();
    return JournalEntry.newBuilder().setCreateStore(createStore).build();
  }

  private JournalEntry newCompletePartitionEntry(long fileId, PartitionInfo info) {
    CompletePartitionEntry completePartition =
        CompletePartitionEntry.newBuilder().setStoreId(fileId).setBlockId(info.getBlockId())
            .setKeyStart(new String(info.bufferForKeyStart().array()))
            .setKeyLimit(new String(info.bufferForKeyLimit().array()))
            .setKeyCount(info.getKeyCount()).build();
    return JournalEntry.newBuilder().setCompletePartition(completePartition).build();
  }

  private JournalEntry newCompleteStoreEntry(long fileId) {
    CompleteStoreEntry completeStore = CompleteStoreEntry.newBuilder().setStoreId(fileId).build();
    return JournalEntry.newBuilder().setCompleteStore(completeStore).build();
  }

  private JournalEntry newDeleteStoreEntry(long fileId) {
    DeleteStoreEntry deleteStore = DeleteStoreEntry.newBuilder().setStoreId(fileId).build();
    return JournalEntry.newBuilder().setDeleteStore(deleteStore).build();
  }

  private JournalEntry newRenameStoreEntry(long oldFileId, long newFileId) {
    RenameStoreEntry renameStore = RenameStoreEntry.newBuilder().setOldStoreId(oldFileId)
        .setNewStoreId(newFileId).build();
    return JournalEntry.newBuilder().setRenameStore(renameStore).build();
  }

  private JournalEntry newMergeStoreEntry(long fromFileId, long toFileId) {
    MergeStoreEntry mergeStore = MergeStoreEntry.newBuilder().setFromStoreId(fromFileId)
        .setToStoreId(toFileId).build();
    return JournalEntry.newBuilder().setMergeStore(mergeStore).build();
  }

  private Iterator<JournalEntry> getJournalEntryIterator(
      Map<Long, List<PartitionInfo>> storeToPartitions) {
    final Iterator<Map.Entry<Long, List<PartitionInfo>>> it =
        storeToPartitions.entrySet().iterator();
    return new Iterator<JournalEntry>() {
      // Initial state: mEntry == null, mInfoIterator == null
      // hasNext: mEntry == null, mInfoIterator == null, it.hasNext()
      // mEntry == null, mInfoIterator == null, => create
      // mEntry != null, mInfoIterator.hasNext() => partitions
      // mEntry != null, !mInfoIterator.hasNext() => complete
      private Map.Entry<Long, List<PartitionInfo>> mEntry;
      private Iterator<PartitionInfo> mInfoIterator;

      @Override
      public boolean hasNext() {
        if (mEntry == null && mInfoIterator == null && !it.hasNext()) {
          return false;
        }
        return true;
      }

      @Override
      public JournalEntry next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        if (mEntry == null) {
          Preconditions.checkState(mInfoIterator == null);
          mEntry = it.next();
          mInfoIterator = mEntry.getValue().iterator();
          return newCreateStoreEntry(mEntry.getKey());
        }

        if (mInfoIterator.hasNext()) {
          return newCompletePartitionEntry(mEntry.getKey(), mInfoIterator.next());
        }

        JournalEntry completeEntry = newCompleteStoreEntry(mEntry.getKey());
        mEntry = null;
        mInfoIterator = null;
        return completeEntry;
      }

      @Override
      public void remove() {
        throw new UnsupportedOperationException("remove is not supported.");
      }
    };
  }
}

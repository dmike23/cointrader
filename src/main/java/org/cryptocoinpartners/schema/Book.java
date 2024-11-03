package org.cryptocoinpartners.schema;

import io.github.pixee.security.ObjectInputFilters;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.persistence.Entity;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.Lob;
import javax.persistence.ManyToOne;
import javax.persistence.PostLoad;
import javax.persistence.PostPersist;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import javax.persistence.Transient;

import org.cryptocoinpartners.enumeration.PersistanceAction;
import org.cryptocoinpartners.schema.dao.BookDao;
import org.cryptocoinpartners.schema.dao.Dao;
import org.cryptocoinpartners.util.EM;
import org.cryptocoinpartners.util.Visitor;
import org.joda.time.Instant;
import org.joda.time.Interval;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;

import jline.internal.Log;

/**
 * Book represents a snapshot of all the limit orders for a Market. Book has a "compact" database
 * representation
 *
 * @author Tim Olson
 */
@SuppressWarnings("UnusedDeclaration")
@Entity
// @Cacheable(false)
// @Cache(usage = CacheConcurrencyStrategy.NONSTRICT_READ_WRITE, region = "book")
@Table(indexes = {@Index(columnList = "market,time")})
public class Book extends MarketData implements Spread {

  /**
   * Books will be saved in the database as diffs against the previous Book, but a full Book will be
   * saved if the number of parent hops to the previous full Book reaches MAX_PARENT_CHAIN_LENGTH
   */
  private static final int MAX_PARENT_CHAIN_LENGTH = 20;

  @Inject protected static transient BookFactory bookFactory;
  @Inject protected transient BookDao bookDao;

  public static void find(Interval timeInterval, Visitor<Book> visitor) {
    EM.queryEach(
        Book.class,
        visitor,
        "select b from Book b where time > ?1 and time < ?2",
        timeInterval.getStartMillis(),
        timeInterval.getEndMillis());
  }

  public static void findAll(Visitor<Book> visitor) {
    EM.queryEach(Book.class, visitor, "select b from Book b");
  }

  private static final Object lock = new Object();

  @Transient
  public List<Offer> getBids() {
    // synchronized (lock) {
    resolveDiff();
    if (bids == null) return (new ArrayList<>());
    return bids;
    // }
  }

  @Transient
  public List<Offer> getAsks() {
    //  synchronized (lock) {
    resolveDiff();
    if (asks == null) return (new ArrayList<>());
    return asks;
    // }
  }

  @Override
  @Transient
  public Offer getBestBid() {
    if ((getBids() == null || getBids().isEmpty()))
      return new Offer(getMarket(), getTime(), getTimeReceived(), 0L, 0L);
    return getBids().get(0);
  }

  @Override
  @Transient
  public Offer getBestBidByVolume(DiscreteAmount volume) {
    long remainingVolume = Math.abs(volume.getCount());

    Offer bid = getBestBid();

    if (getBids() != null && !getBids().isEmpty()) {

      bid = getBids().get(getBids().size() - 1);

      for (Offer bookBid : getBids()) {
        if (bookBid.getVolumeCount() >= remainingVolume) {
          bid = bookBid;
          break;
        } else remainingVolume = Math.max(remainingVolume - bid.getVolumeCount(), 0);
      }
    }

    return bid;
  }

  @Override
  @Transient
  public Offer getBestAsk() {
    if ((getAsks() == null || getAsks().isEmpty())) {
      return new Offer(getMarket(), getTime(), getTimeReceived(), 0L, 0L);
    }
    return getAsks().get(0);
  }

  @Override
  @Transient
  public Offer getBestAskByVolume(DiscreteAmount volume) {
    long remainingVolume = Math.abs(volume.getCount()) * -1;
    // what about not enough volume on book!
    Offer ask = getBestAsk();
    if (getAsks() != null && !getAsks().isEmpty()) {
      ask = getAsks().get(getAsks().size() - 1);
      for (Offer bookAsk : getAsks()) {
        if (bookAsk.getVolumeCount() <= remainingVolume) {
          ask = bookAsk;
          break;
        } else remainingVolume = Math.min(remainingVolume - ask.getVolumeCount(), 0);
      }
    }
    return ask;
  }

  @Nullable
  @Transient
  public DiscreteAmount getBidPrice() {
    if (getBids().isEmpty()) return new DiscreteAmount(0, (getMarket()).getPriceBasis());
    return getBids().get(0).getPrice();
  }

  @Nullable
  @Transient
  public DiscreteAmount getBidVolume() {
    if (getBids().isEmpty()) return new DiscreteAmount(0, getMarket().getVolumeBasis());
    return getBids().get(0).getVolume();
  }

  @Nullable
  public Double getBidPriceAsDouble() {
    if (getBids() == null || getBids().isEmpty()) return 0d;
    return getBids().get(0).getPriceAsDouble();
  }

  @Nullable
  @Transient
  public Double getBidPriceCountAsDouble() {
    if (getBids() == null || getBids().isEmpty()) return 0d;
    return getBids().get(0).getPriceCountAsDouble();
  }

  @Nullable
  public Double getBidVolumeAsDouble() {
    if (getBids() == null || getBids().isEmpty()) return 0d;
    return getBids().get(0).getVolumeAsDouble();
  }

  @Nullable
  @Transient
  public Double getBidVolumeCountAsDouble() {
    if (getBids() == null || getBids().isEmpty()) return 0d;
    return getBids().get(0).getVolumeCountAsDouble();
  }

  @Nullable
  @Transient
  public DiscreteAmount getAskPrice() {
    if (getAsks() == null || getAsks().isEmpty())
      return new DiscreteAmount(0L, getMarket().getPriceBasis());
    return getAsks().get(0).getPrice();
  }

  @Nullable
  @Transient
  public DiscreteAmount getAskVolume() {
    if (getAsks() == null || getAsks().isEmpty())
      return new DiscreteAmount(0, getMarket().getVolumeBasis());
    return getAsks().get(0).getVolume();
  }

  @Override
  @Nullable
  @Transient
  public BookDao getDao() {

    return bookDao;
  }

  @Override
  @Transient
  public synchronized void setDao(Dao dao) {
    bookDao = (BookDao) dao;
    // TODO Auto-generated method stub
    //  return null;
  }

  /** saved to the db for query convenience */
  @Nullable
  public Double getAskPriceAsDouble() {
    if (getAsks() == null || getAsks().isEmpty()) return 0d;
    Double ask = getAsks().get(0).getPriceAsDouble();
    return ask;
  }

  @Nullable
  @Transient
  public Double getAskPriceCountAsDouble() {
    if (getAsks() == null || getAsks().isEmpty()) return 0d;
    return getAsks().get(0).getPriceCountAsDouble();
  }

  /** saved to the db for query convenience */
  @Nullable
  public Double getAskVolumeAsDouble() {
    if (getAsks() == null || getAsks().isEmpty()) return 0d;
    return getAsks().get(0).getVolumeAsDouble();
  }

  @Nullable
  @Transient
  public Double getAskVolumeCountAsDouble() {
    if (getAsks() == null || getAsks().isEmpty()) return 0d;
    return getAsks().get(0).getVolumeCountAsDouble();
  }

  public static class DiffResult {
    List<Offer> newOffers = new ArrayList<>();
    List<Offer> removedOffers = new ArrayList<>();
  }

  public DiffResult diff(Book previousBook) {
    DiffResult result = new DiffResult();
    //   synchronized (lock) {
    diff(result, getBids(), previousBook.getBids());
    diff(result, getAsks(), previousBook.getAsks());
    //  }
    return result;
  }

  @AssistedInject
  Book(@Assisted Instant time, @Assisted Tradeable market) {
    //  this.bookDao = bookDao;
    // Book();
    if (time.getMillis() == 1391306423000L) log.debug("test");
    this.uuid = getUuid();

    this.bids = new ArrayList<>();
    this.asks = new ArrayList<>();
    this.setTime(time);
    this.setTimeReceived(Instant.now());
    this.setRemoteKey(null);
    this.setMarket(market);
  }

  @AssistedInject
  Book(@Assisted Instant time, @Assisted String remoteKey, @Assisted Tradeable market) {
    // Book();
    // this.bookDao = bookDao;
    if (time.getMillis() == 1391306423000L) log.debug("test");
    this.uuid = getUuid();
    this.bids = new ArrayList<>();
    this.asks = new ArrayList<>();
    this.setTime(time);
    this.setTimeReceived(Instant.now());
    this.setRemoteKey(remoteKey);
    this.setMarket(market);
  }

  @AssistedInject
  Book(
      @Assisted("bookTime") Instant time,
      @Assisted("bookTimeReceived") Instant timeReceived,
      @Assisted String remoteKey,
      @Assisted Tradeable market) {
    if (time.getMillis() == 1391306423000L) log.debug("test");
    this.uuid = getUuid();
    this.bids = new ArrayList<>();
    this.asks = new ArrayList<>();
    this.setTime(time);
    this.setTimeReceived(timeReceived);
    this.setRemoteKey(remoteKey);
    this.setMarket(market);
  }

  public synchronized Book addBid(BigDecimal price, BigDecimal volume) {
    //   synchronized (lock) {
    if (time.getMillis() == 1391306423000L) log.debug("test");
    Tradeable market = this.getMarket();
    synchronized (this.bids) {
      this.bids.add(
          Offer.bid(
              market,
              this.getTime(),
              this.getTimeReceived(),
              DiscreteAmount.roundedCountForBasis(price, market.getPriceBasis()),
              DiscreteAmount.roundedCountForBasis(volume, market.getVolumeBasis())));
    }
    return this;

    //   }

  }

  public <T> T queryZeroOne(Class<T> resultType, String queryStr, Object... params) {

    //  em = createEntityManager();
    return bookDao.queryZeroOne(resultType, queryStr, params);
  }

  public Book addAsk(BigDecimal price, BigDecimal volume) {
    if (time.getMillis() == 1391306423000L) log.debug("test");
    Tradeable market = this.getMarket();
    synchronized (this.asks) {
      this.asks.add(
          Offer.ask(
              market,
              this.getTime(),
              this.getTimeReceived(),
              DiscreteAmount.roundedCountForBasis(price, market.getPriceBasis()),
              DiscreteAmount.roundedCountForBasis(volume, market.getVolumeBasis())));
    }
    return this;

    //   }

  }

  public Book build() {
    this.sortBook();

    // look for a Chain of Books of the same Market
    String marketSymbol = this.getMarket().getSymbol();
    // the chinas use quit a lot of memorry.
    Chain chain = chains.get(marketSymbol);
    if (chain == null) {
      // no chain exists for the Market, so create one
      chain = new Chain();
      chain.previousBook = this;
      chains.put(marketSymbol, chain);
    } else {
      // a parent Book exists in the chain
      Book parentBook;
      if (chain.chainLength == MAX_PARENT_CHAIN_LENGTH) {
        // reached max chain length.  set parent to null and reset the chain length count
        parentBook = null;
        chain.chainLength = 0;
      } else {
        // the chain is not too long.  use the previous book in the chain as a parent
        parentBook = chain.previousBook;
        chain.chainLength++;
      }

      this.setParent(parentBook);
      chain.previousBook = this;
    }

    Book result = this;
    return result;
  }

  /** Book.Builder remembers the previous Book it built, allowing for diffs to be saved in the db */
  public static class Builder {

    public Builder() {
      // this.book = bookFactory.create(true);
      this.book = Book.create();
    }

    public void start(Instant time, String remoteKey, Market market) {
      book.setTime(time);
      book.setTimeReceived(Instant.now());
      book.setRemoteKey(remoteKey);
      book.setMarket(market);
    }

    public void start(Instant time, Instant timeReceived, String remoteKey, Market market) {
      book.setTime(time);
      book.setTimeReceived(timeReceived);
      book.setRemoteKey(remoteKey);
      book.setMarket(market);
    }

    public Builder addBid(BigDecimal price, BigDecimal volume) {

      Tradeable market = book.getMarket();
      //   synchronized (lock) {

      volume = volume.compareTo(BigDecimal.ZERO) > 0 ? volume : volume.negate();

      book.bids.add(
          Offer.bid(
              market,
              book.getTime(),
              book.getTimeReceived(),
              DiscreteAmount.roundedCountForBasis(price, market.getPriceBasis()),
              DiscreteAmount.roundedCountForBasis(volume, market.getVolumeBasis())));

      return this;
    }

    public Builder addAsk(BigDecimal price, BigDecimal volume) {
      Tradeable market = book.getMarket();
      // synchronized (lock) {
      volume = volume.compareTo(BigDecimal.ZERO) < 0 ? volume : volume.negate();
      book.asks.add(
          Offer.ask(
              market,
              book.getTime(),
              book.getTimeReceived(),
              DiscreteAmount.roundedCountForBasis(price, market.getPriceBasis()),
              DiscreteAmount.roundedCountForBasis(volume, market.getVolumeBasis())));
      //  }

      return this;
    }

    public Book build() {
      book.sortBook();

      // look for a Chain of Books of the same Market
      String marketSymbol = book.getMarket().getSymbol();
      Chain chain = chains.get(marketSymbol);
      if (chain == null) {
        // no chain exists for the Market, so create one
        chain = new Chain();
        chain.previousBook = book;
        chains.put(marketSymbol, chain);
      } else {
        // a parent Book exists in the chain
        Book parentBook;
        if (chain.chainLength == MAX_PARENT_CHAIN_LENGTH) {
          // reached max chain length.  set parent to null and reset the chain length count
          parentBook = null;
          chain.chainLength = 0;
        } else {
          // the chain is not too long.  use the previous book in the chain as a parent
          parentBook = chain.previousBook;
          chain.chainLength++;
        }

        book.setParent(parentBook);
        chain.previousBook = book;
      }

      Book result = book;
      book = Book.create();
      return result;
    }

    private static class Chain {
      private int chainLength;
      private Book previousBook;
      private String marketSymbol;
    }

    private Book book;
    private final Map<String, Chain> chains = new HashMap<>();
  }

  private static class Chain {
    private int chainLength;
    private Book previousBook;
    private String marketSymbol;
  }

  @Override
  public String toString() {
    StringBuilder sb =
        new StringBuilder(getMarket().toString() + " Book at " + getTime() + " bids={");
    boolean first = true;
    for (Offer bid : getBids()) {
      if (first) first = false;
      else sb.append(';');
      sb.append(bid.getVolumeAsDouble());
      sb.append('@');
      sb.append(bid.getPriceAsDouble());
    }
    sb.append("} asks={");
    first = true;
    for (Offer ask : getAsks()) {
      if (first) first = false;
      else sb.append(';');
      sb.append(ask.getVolumeAsDouble());
      sb.append('@');
      sb.append(ask.getPriceAsDouble());
    }
    sb.append('}');
    return sb.toString();
  }

  // JPA
  protected Book() {}

  // These getters and setters are for conversion in JPA
  // @ManyToOne(cascade = CascadeType.MERGE)
  // @JoinColumn(name = "parent", insertable = false, updatable = false)

  // @ManyToOne(optional = true, cascade = CascadeType.MERGE)
  // @Column
  // (name = "parent_book", columnDefinition = "binary", table = "book")
  //  @OneToOne(cascade = CascadeType.ALL)
  // @PrimaryKeyJoinColumn
  // cascade = { CascadeType.REFRESH, CascadeType.MERGE }
  @Override
  @ManyToOne(optional = true)
  @JoinColumn(name = "parent")
  // , cascade = { CascadeType.REFRESH, CascadeType.MERGE, CascadeType.PERSIST })
  public Book getParent() {
    return parent;
  }

  // @Nullable
  // @OneToMany
  // (cascade = { CascadeType.MERGE, CascadeType.REMOVE })
  //  public Collection<Book> getChildren() {
  //      if (children == null)
  //          children = new ArrayList<Book>();
  //      synchronized (lock) {
  //          return children;
  //      }
  //  }
  //
  //  public void addChild(Book book) {
  //      synchronized (lock) {
  //          getChildren().add(book);
  //      }
  //  }

  protected @Lob byte[] getBidDeletionsBlob() {
    return bidDeletionsBlob;
  }

  protected @Lob byte[] getAskDeletionsBlob() {
    return askDeletionsBlob;
  }

  protected @Lob byte[] getBidInsertionsBlob() {
    return bidInsertionsBlob;
  }

  protected @Lob byte[] getAskInsertionsBlob() {
    return askInsertionsBlob;
  }

  //  protected void setChildren(List<Book> children) {
  //      this.children = children;
  //  }

  protected synchronized void setParent(Book parent) {
    this.parent = parent;
  }

  protected synchronized void setBidDeletionsBlob(byte[] bidDeletionsBlob) {
    this.bidDeletionsBlob = bidDeletionsBlob;
  }

  protected synchronized void setAskDeletionsBlob(byte[] askDeletionsBlob) {
    this.askDeletionsBlob = askDeletionsBlob;
  }

  protected synchronized void setBidInsertionsBlob(byte[] bidInsertionsBlob) {
    this.bidInsertionsBlob = bidInsertionsBlob;
  }

  protected synchronized void setChildren(List<Book> children) {
    this.children = children;
  }

  protected synchronized void setAskInsertionsBlob(byte[] askInsertionsBlob) {
    this.askInsertionsBlob = askInsertionsBlob;
  }

  // these fields are derived from the blobs
  protected synchronized void setBidPriceAsDouble(
      @SuppressWarnings("UnusedParameters") Double ignored) {}

  protected synchronized void setBidVolumeAsDouble(
      @SuppressWarnings("UnusedParameters") Double ignored) {}

  protected synchronized void setAskPriceAsDouble(
      @SuppressWarnings("UnusedParameters") Double ignored) {}

  protected synchronized void setAskVolumeAsDouble(
      @SuppressWarnings("UnusedParameters") Double ignored) {}

  // this is separate from the empty JPA constructor.  it allows Book.Builder to start with a
  // minimally initialized Book
  private static Book create() {
    Book result = new Book();
    result.bids = new ArrayList<>();
    result.asks = new ArrayList<>();
    return result;
  }

  private Book(boolean init) {
    Book result = new Book();
    result.bids = new ArrayList<>();
    result.asks = new ArrayList<>();
  }

  @Override
  @PrePersist
  public synchronized void prePersist() {

    //  if (parent != null)
    //    if (parent.find() == null)
    //      parent.persit();
    if (parent == null) {

      // PersistUtil.insert(getMarket());
      if (bids != null) bidInsertionsBlob = convertQuotesToDatabaseBlob(bids);
      if (asks != null) askInsertionsBlob = convertQuotesToDatabaseBlob(asks);
      bidDeletionsBlob = null;
      askDeletionsBlob = null;
    } else {
      if (!parent.isPersisted() && parent.getDao() != null) parent.persit();
      /// if (this.getDao() != null) {
      // UUID duplicate = this.queryZeroOne(UUID.class, "select b.id from Book b where b.id=?1",
      // parent.getId());

      // if (duplicate == null)
      //	parent.persit();
      // }
      // PersistUtil.find(getParentBook());
      // PersistUtil.refresh(this);
      // PersistUtil.merge(this);
      // PersistUtil.merge(getParentBook());
      // .refresh(getParentBook());
      // PersistUtil.detach(parent);
      //  PersistUtil.refresh(getParentBook());
      DiffBlobs bidBlobs = diff(parent.getBids(), getBids());
      bidInsertionsBlob = bidBlobs.insertBlob;
      bidDeletionsBlob = bidBlobs.deleteBlob;
      DiffBlobs askBlobs = diff(parent.getAsks(), getAsks());
      askInsertionsBlob = askBlobs.insertBlob;
      askDeletionsBlob = askBlobs.deleteBlob;
    }
  }

  @SuppressWarnings("ConstantConditions")
  private boolean hasQuote(List<? extends Offer> list, Offer offer) {
    for (Offer item : list) {
      if (Long.compare(item.getPriceCount(), offer.getPriceCount()) == 0
          && Long.compare(item.getVolumeCount(), offer.getVolumeCount()) == 0) return true;
    }
    return false;
  }

  @Override
  @PostPersist
  public synchronized void postPersist() {
    if (this.parent != null) parent.detach();

    // detach();
    // detach();
    clearBlobs();
  }

  @PostLoad
  private void postLoad() {
    if (time.getMillis() == 1391306423000L) log.debug("test");
    bids = convertDatabaseBlobToQuoteList(bidInsertionsBlob);
    asks = convertDatabaseBlobToQuoteList(askInsertionsBlob);
    if (parent != null) {
      needToResolveDiff = true;

      parent.detach();
    }
    // if (this.parent != null)

    // detach();
    // detach();
  }

  // if this is implemented as a @PostLoad, the transitive dependencies for the parent's parent are
  // not resolved
  private void resolveDiff() {
    if (!needToResolveDiff) return;
    // no difference between books
    // if (bidDeletionsBlob == null || askDeletionsBlob == null)
    // return;
    if (bidDeletionsBlob == null || askDeletionsBlob == null) Log.debug("null blob");
    // add any non-deleted entries from the parent
    List<Integer> bidDeletionIndexes =
        convertDatabaseBlobToIndexList(bidDeletionsBlob); // these should be already sorted
    List<Offer> parentBids = parent.getBids();
    for (int i = 0; i < parentBids.size(); i++) {
      if (!bidDeletionIndexes.contains(i))
        //  synchronized (lock) {
        bids.add(parentBids.get(i));
      // }
    }
    List<Integer> askDeletionIndexes =
        convertDatabaseBlobToIndexList(askDeletionsBlob); // these should be already sorted
    List<Offer> parentAsks = parent.getAsks();
    for (int i = 0; i < parentAsks.size(); i++) {
      if (!askDeletionIndexes.contains(i))
        //   synchronized (lock) {
        asks.add(parentAsks.get(i));
      //    }
    }
    sortBook();
    clearBlobs();
    needToResolveDiff = false;
  }

  private void clearBlobs() {
    // parent = null;
    bidDeletionsBlob = null;
    askDeletionsBlob = null;
    bidInsertionsBlob = null;
    askInsertionsBlob = null;
  }

  @SuppressWarnings("ConstantConditions")
  private static <T extends Offer> byte[] convertQuotesToDatabaseBlob(List<T> quotes) {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try {
      ObjectOutputStream out = new ObjectOutputStream(bos);
      out.writeInt(quotes.size());
      for (T quote : quotes) {
        out.writeLong(quote.getPriceCount());
        out.writeLong(quote.getVolumeCount());
      }
      out.close();
      bos.close();
    } catch (IOException e) {
      throw new Error(e);
    }
    return bos.toByteArray();
  }

  private List<Offer> convertDatabaseBlobToQuoteList(byte[] bytes) {

    if (bytes == null) return new ArrayList<>();
    if (time.getMillis() == 1391306423000L) log.debug("test");
    List<Offer> result = new ArrayList<>();
    ByteArrayInputStream bin = new ByteArrayInputStream(bytes);
    //noinspection EmptyCatchBlock
    try {
      ObjectInputStream in = new ObjectInputStream(bin);
      ObjectInputFilters.enableObjectFilterIfUnprotected(in);
      int size = in.readInt();
      for (int i = 0; i < size; i++) {
        long price = in.readLong();
        long volume = in.readLong();

        result.add(new Offer(getMarket(), getTime(), getTimeReceived(), price, volume));
      }
      in.close();
      bin.close();
    } catch (IOException e) {
      throw new Error(e);
    }
    return result;
  }

  @SuppressWarnings("ConstantConditions")
  private static byte[] convertIndexesToDatabaseBlob(List<Integer> indexes) {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try {
      ObjectOutputStream out = new ObjectOutputStream(bos);
      out.writeInt(indexes.size());
      for (Integer index : indexes) out.writeInt(index);
      out.close();
      bos.close();
    } catch (IOException e) {
      throw new Error(e);
    }
    return bos.toByteArray();
  }

  private static List<Integer> convertDatabaseBlobToIndexList(byte[] bytes) {
    List<Integer> result = new ArrayList<>();
    if (bytes == null) return result;
    ByteArrayInputStream bin = new ByteArrayInputStream(bytes);
    try {
      ObjectInputStream in = new ObjectInputStream(bin);
      ObjectInputFilters.enableObjectFilterIfUnprotected(in);
      int size = in.readInt();
      for (int i = 0; i < size; i++) result.add(in.readInt());
      in.close();
      bin.close();
    } catch (IOException e) {
      throw new Error(e);
    }
    return result;
  }

  /** this implements the public diff() */
  private void diff(
      DiffResult result, List<? extends Offer> childQuotes, List<? extends Offer> parentQuotes) {
    for (Offer childOffer : childQuotes) {
      if (!hasQuote(parentQuotes, childOffer)) result.newOffers.add(childOffer);
    }
    for (Offer parentOffer : parentQuotes) {
      if (!hasQuote(childQuotes, parentOffer)) result.removedOffers.add(parentOffer);
    }
  }

  private static class DiffBlobs {
    byte[] insertBlob;
    byte[] deleteBlob;
  }

  /** this is separate from the public diff for efficiency */
  private DiffBlobs diff(List<? extends Offer> parentQuotes, List<? extends Offer> childQuotes) {
    List<Offer> insertions = new ArrayList<>();
    for (Offer childOffer : childQuotes) {
      if (!hasQuote(parentQuotes, childOffer)) insertions.add(childOffer);
    }

    List<Integer> deletionIndexes = new ArrayList<>();
    for (int i = 0; i < parentQuotes.size(); i++) {
      Offer offer = parentQuotes.get(i);
      if (!hasQuote(childQuotes, offer)) deletionIndexes.add(i);
    }
    DiffBlobs result = new DiffBlobs();
    result.insertBlob = convertQuotesToDatabaseBlob(insertions);
    result.deleteBlob = convertIndexesToDatabaseBlob(deletionIndexes);
    return result;
  }

  public void sortBook() {
    // sort price high to low, then by oldest fist, then by largest volumes
    Collections.sort(
        bids,
        new Comparator<Offer>() {
          @Override
          @SuppressWarnings("ConstantConditions")
          public int compare(Offer bid, Offer bid2) {

            int pComp = bid2.getPriceCount().compareTo(bid.getPriceCount()); // high to low
            if (pComp != 0) return pComp;

            int tComp = bid.getTime().compareTo(bid2.getTime()); // oldest to newest
            if (tComp != 0) return tComp;

            int vComp =
                bid2.getVolumeCount().compareTo(bid.getVolumeCount()); // biggest to smallest
            if (vComp != 0) return vComp;

            return System.identityHashCode(bid) - System.identityHashCode(bid2);
          }
        });
    //    }
    // sort price low to high, then by oldest fist, then by largest volume
    Collections.sort(
        asks,
        new Comparator<Offer>() {
          @Override
          @SuppressWarnings("ConstantConditions")
          public int compare(Offer ask, Offer ask2) {

            int pComp = ask.getPriceCount().compareTo(ask2.getPriceCount()); // low to high
            if (pComp != 0) return pComp;

            int tComp = ask.getTime().compareTo(ask2.getTime()); // oldest to newest
            if (tComp != 0) return tComp;

            int vComp =
                ask2.getVolumeCount().compareTo(ask.getVolumeCount()); // biggest to smallest
            if (vComp != 0) return vComp;

            return System.identityHashCode(ask) - System.identityHashCode(ask2);
          }
        });
    //   }
  }

  public <T> T find() {
    //   synchronized (persistanceLock) {
    try {
      return (T) bookDao.find(Book.class, this.getId());
      // if (duplicate == null || duplicate.isEmpty())
    } catch (Exception | Error ex) {
      return null;
      // System.out.println("Unable to perform request in " + this.getClass().getSimpleName() +
      // ":find, full stack trace follows:" + ex);
      // ex.printStackTrace();

    }
  }

  //    public <T> T findByReference() {
  //        //   synchronized (persistanceLock) {
  //        try {
  //            return (T) bookDao.getReference(Book.class, this.getId());
  //            //if (duplicate == null || duplicate.isEmpty())
  //        } catch (Exception | Error ex) {
  //            return null;
  //            // System.out.println("Unable to perform request in " +
  // this.getClass().getSimpleName() + ":find, full stack trace follows:" + ex);
  //            // ex.printStackTrace();
  //
  //        }
  //
  //    }
  @Override
  public synchronized EntityBase refresh() {
    return bookDao.refresh(this);
  }

  @Override
  public synchronized void persit() {
    try {
      //  if (parent != null)
      //    if (parent.find() == null)
      //      parent.persit();
      this.setPeristanceAction(PersistanceAction.NEW);

      this.setRevision(this.getRevision() + 1);

      bookDao.persist(this);

    } catch (javax.persistence.PersistenceException pex) {
      System.out.println(
          "Unable to perist entity "
              + this.getClass().getSimpleName()
              + ": "
              + this.getUuid()
              + ". "
              + pex.getCause());

    } catch (Exception | Error ex) {

      //     unitOfWork.end();
      System.out.println(
          "Unable to perist entity "
              + this.getClass().getSimpleName()
              + ": "
              + this.getUuid()
              + ". "
              + ex);
      throw ex;
    }
    // Do transactions, queries, etc...

  }

  @Override
  public synchronized void detach() {
    try {
      if (bookDao != null) bookDao.detach(this);
    } catch (Exception | Error ex) {

    }
  }

  private static final Map<String, Chain> chains = new HashMap<>();

  // @Inject
  // private FillJpaDao fillDao;
  private List<Offer> bids;
  private List<Offer> asks;
  private List<Book> children;
  private Book
      parent; // if this is not null, then the Book is persisted as a diff against the parent Book
  private byte[] bidDeletionsBlob;
  private byte[] askDeletionsBlob;
  private byte[] bidInsertionsBlob;
  private byte[] askInsertionsBlob;
  private boolean needToResolveDiff;

  // private Collection<Book> children;

  @Override
  public synchronized void merge() {
    this.setPeristanceAction(PersistanceAction.MERGE);

    this.setRevision(this.getRevision() + 1);
    bookDao.merge(this);
  }

  @Override
  public void delete() {
    // TODO Auto-generated method stub

  }

  @Override
  public void persitParents() {
    //	if (parent != null)
    //	parent.persit();
    // Sometimes the pqrent does not persit, causing all persistance messages to backup and loop.
    // Just better to restart the order book chain.
    setParent(null);

    /*
     * try { // if (parent != null) // if (parent.find() == null) // parent.persit(); getParent().setPeristanceAction(PersistanceAction.NEW);
     * getParent().setRevision(getParent().getRevision() + 1); bookDao.persist(getParent()); } catch (javax.persistence.PersistenceException pex) {
     * System.out.println("Unable to  perist parnet entity " + getParent().getClass().getSimpleName() + ": " + getParent().getId() + ". " +
     * pex.getCause()); } catch (Exception | Error ex) { // unitOfWork.end(); System.out.println("Unable to persit parnet  entity " +
     * getParent().getClass().getSimpleName() + ": " + getParent().getId() + ". " + ex); throw ex; }
     */
    // Do transactions, queries, etc...

  }
}

package org.cryptocoinpartners.module;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;
import javax.inject.Singleton;

import org.cryptocoinpartners.esper.annotation.When;
import org.cryptocoinpartners.schema.Asset;
import org.cryptocoinpartners.schema.Bar;
import org.cryptocoinpartners.schema.Book;
import org.cryptocoinpartners.schema.Currency;
import org.cryptocoinpartners.schema.DiscreteAmount;
import org.cryptocoinpartners.schema.Exchanges;
import org.cryptocoinpartners.schema.Listing;
import org.cryptocoinpartners.schema.Market;
import org.cryptocoinpartners.schema.Offer;
import org.cryptocoinpartners.schema.Trade;
import org.cryptocoinpartners.schema.Tradeable;
import org.cryptocoinpartners.service.QuoteService;
import org.cryptocoinpartners.util.ConfigUtil;
import org.cryptocoinpartners.util.ListingsMatrix;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

/**
 * This service listens to the Context and caches the most recent Trades and Books
 *
 * @author Tim Olson
 */
@SuppressWarnings("UnusedDeclaration")
@Singleton
public class BasicQuoteService implements QuoteService {
  protected static boolean seedUSDT =
      (ConfigUtil.combined() != null)
          ? ConfigUtil.combined().getBoolean("marketdata.implied.usdt", true)
          : false;

  public static class DataSubscriber {

    public void update(double avgValue, double countValue, double minValue, double maxValue) {
      log.info(avgValue + "," + countValue + "," + minValue + "," + maxValue);
    }

    public void update(Bar bar) {
      log.info("PrevioiusBar:" + bar.toString());
    }

    public void update(Tradeable market, double interval, double ewma) {

      log.info("expvariance " + ewma + " for itnerval " + interval + " and market " + market);
    }
  }

  @Override
  public Trade getLastTrade(Tradeable market) {
    if (market == null) return null;
    /*
     * if (XchangeData.exists()) { XchangeData xchangeData = context.getInjector().getInstance(XchangeData.class); try { for (Trade trade :
     * xchangeData.getTrades(market, market.getExchange())) recordTrade(trade); } catch (Throwable e) { // TODO Auto-generated catch block
     * log.error(this.getClass().getSimpleName() + ": getLastTrade - Unable to retrive latest trades for market", e); } }
     */
    return lastTradeByMarket.get(market.getSymbol());
  }

  @Override
  public Bar getLastBar(Tradeable market, double interval) {
    if (market == null
        || lastBarByMarket == null
        || lastBarByMarket.get(market.getSymbol()) == null
        || lastBarByMarket.get(market.getSymbol()).get(interval) == null) return null;
    /*
     * if (XchangeData.exists()) { XchangeData xchangeData = context.getInjector().getInstance(XchangeData.class); try { for (Trade trade :
     * xchangeData.getTrades(market, market.getExchange())) recordTrade(trade); } catch (Throwable e) { // TODO Auto-generated catch block
     * log.error(this.getClass().getSimpleName() + ": getLastTrade - Unable to retrive latest trades for market", e); } }
     */
    return lastBarByMarket.get(market.getSymbol()).get(interval);
  }

  @Override
  public Trade getLastTrade(Listing listing) {
    if (listing == null) return null;

    /*
     * if (XchangeData.exists()) { XchangeData xchangeData = context.getInjector().getInstance(XchangeData.class); try { for (Market market :
     * getMarketsForListing(listing)) for (Trade trade : xchangeData.getTrades(market, market.getExchange())) recordTrade(trade); } catch (Throwable
     * e) { // TODO Auto-generated catch block log.error(this.getClass().getSimpleName() +
     * ": getLastTrade - Unable to retrive latest trades for market", e); } }
     */

    if (lastTradeByListing.get(listing.getSymbol()) != null)
      return lastTradeByListing.get(listing.getSymbol());
    else {
      //		log.debug(this.getClass().getSimpleName()
      //				+ ":getLastTrade - Unabled to get trade directly from listings, calcuating implied price
      // from impliedTradeMatrix " + impliedTradeMatrix);

      return getLastImpliedTrade(listing);
    }
  }

  @Override
  public Trade getLastImpliedTrade(Listing listing) {
    if (listing == null) return null;
    try {
      DiscreteAmount impliedPrice =
          impliedTradeMatrix.getRate(listing.getBase(), listing.getQuote());
      Market market =
          context.getInjector().getInstance(Market.class).findOrCreate(Exchanges.SELF, listing);
      // long impliedPriceCount = impliedTradeMatrix.getRate(listing.getBase(),
      // listing.getQuote()).getCount();

      long impliedPriceCount =
          DiscreteAmount.roundedCountForBasis(impliedPrice.asBigDecimal(), market.getPriceBasis());
      // issues is that implied price is in 0.001 baisis, and market is in in 0.01

      Trade trade =
          new Trade(market, Instant.now(), Instant.now().toString(), impliedPriceCount, 0L);
      log.trace(
          "{}:getLastImpliedTrade - Calcaulted implied trade={} , listing={} , impliedTradeMatrix={}",
          this.getClass().getSimpleName(),
          trade.getPrice(),
          listing,
          impliedTradeMatrix);
      return trade;
    } catch (java.lang.IllegalArgumentException e) {
      return null;
    }
  }

  @Override
  public Book getLastBook(Tradeable market) {
    if (market == null) return null;
    /*
     * if (XchangeData.exists()) { XchangeData xchangeData = context.getInjector().getInstance(XchangeData.class); try {
     * recordBook(xchangeData.getBook(market, market.getExchange())); } catch (Exception e) { // TODO Auto-generated catch block
     * log.error(this.getClass().getSimpleName() + ": getLastBidForMarket - Unable to retrive latest book for market", e); } }
     */
    return lastBookByMarket.get(market.getSymbol());
  }

  @Override
  public Book getLastBook(Listing listing) {
    if (listing == null) return null;
    /*
     * if (XchangeData.exists()) { XchangeData xchangeData = context.getInjector().getInstance(XchangeData.class); try { for (Market market :
     * getMarketsForListing(listing)) recordBook(xchangeData.getBook(market, market.getExchange())); } catch (Exception e) { // TODO Auto-generated
     * catch block log.error(this.getClass().getSimpleName() + ": getLastBidForMarket - Unable to retrive latest book for market", e); } }
     */
    return lastBookByListing.get(listing.getSymbol());
  }

  @Override
  public Set<Market> getMarketsForListing(Listing listing) {
    if (listing == null) return null;
    Set<Market> result = marketsByListing.get(listing.getSymbol());
    return result == null ? Collections.<Market>emptySet() : result;
  }

  /** @return null if no Books for the given listing have been received yet */
  @Override
  public @Nullable Offer getBestBidForListing(Listing listing) {
    Offer bestBid = null;
    for (Market market : marketsByListing.get(listing.getSymbol())) {
      Book book = bestBidByMarket.get(market.getSymbol());
      Offer testBestBid = book.getBestBid();
      //noinspection ConstantConditions
      if (bestBid == null
          || bestBid.getVolumeCount() == 0
          || bestBid.getPriceCount() == 0
          || (testBestBid != null && testBestBid.getPrice().compareTo(bestBid.getPrice()) > 0))
        bestBid = testBestBid;
    }
    bestBid = ((bestBid == null) ? getImpliedBestAskForListing(listing) : bestBid);

    return bestBid;
  }

  @Override
  public @Nullable Offer getLastBidForMarket(Tradeable market) {
    if (market == null) return null;
    Offer bestBid = null;
    /*
     * if (XchangeData.exists()) { XchangeData xchangeData = context.getInjector().getInstance(XchangeData.class); try {
     * recordBook(xchangeData.getBook(market, market.getExchange())); } catch (Exception e) { // TODO Auto-generated catch block
     * log.error(this.getClass().getSimpleName() + ": getLastBidForMarket - Unable to retrive latest book for market", e); } }
     */

    // XchangeData xchangeData = context.getInjector().getInstance(XchangeData.class);
    // for( Market market : marketsByListing.get(listing.getSymbol()) ) {
    Book book = lastBookByMarket.get(market.getSymbol());
    if (book != null) bestBid = book.getBestBid();

    if (bestBid == null || bestBid.getVolumeCount() == 0 || bestBid.getPriceCount() == 0) {
      DiscreteAmount bestImpliedBidAmount = getLastTrade(market).getPrice();
      long bestImpliedBid =
          DiscreteAmount.roundedCountForBasis(
              bestImpliedBidAmount.asBigDecimal(), market.getPriceBasis());
      DiscreteAmount bestImpliedBidVolumeAmount = getLastTrade(market).getVolume();
      long bestImplieBidVolume =
          Math.abs(
              DiscreteAmount.roundedCountForBasis(
                  bestImpliedBidVolumeAmount.asBigDecimal(), market.getVolumeBasis()));

      bestBid =
          new Offer(market, Instant.now(), Instant.now(), bestImpliedBid, bestImplieBidVolume);
    }

    return bestBid;
  }

  /** @return null if no Books for the given listing have been received yet */
  @Override
  public @Nullable Offer getBestAskForListing(Listing listing) {
    Offer bestAsk = null;
    if (marketsByListing.get(listing.getSymbol()) != null) {
      for (Market market : marketsByListing.get(listing.getSymbol())) {
        Book book = bestAskByMarket.get(market.getSymbol());
        Offer testBestAsk = book.getBestAsk();
        //noinspection ConstantConditions
        if (bestAsk == null
            || bestAsk.getVolumeCount() == 0
            || bestAsk.getPriceCount() == 0
            || (testBestAsk != null && testBestAsk.getPrice().compareTo(bestAsk.getPrice()) < 0))
          bestAsk = testBestAsk;
      }
    }
    bestAsk = ((bestAsk == null) ? getImpliedBestAskForListing(listing) : bestAsk);

    return bestAsk;
  }

  @Override
  // TODO keep a map of markets so we don't hit the db each time.
  public @Nullable Offer getImpliedBestAskForListing(Listing listing) {
    if (listing == null) return null;
    try {
      // we are getting the count and converting it
      // we need to get the descrete amount and rebasis
      DiscreteAmount bestImpliedAskAmount =
          impliedAskMatrix.getRate(listing.getBase(), listing.getQuote());
      Market market =
          context.getInjector().getInstance(Market.class).findOrCreate(Exchanges.SELF, listing);

      long bestImpliedAsk =
          DiscreteAmount.roundedCountForBasis(
              bestImpliedAskAmount.asBigDecimal(), market.getPriceBasis());
      // long bestImpliedAsk = impliedAskMatrix.getRate(listing.getBase(),
      // listing.getQuote()).getCount();

      Offer offer = new Offer(market, Instant.now(), Instant.now(), bestImpliedAsk, 0L);
      log.debug(
          this.getClass().getSimpleName()
              + ":getImpliedBestAskForListing - Calcaulted implied ask="
              + offer.getPrice()
              + ", listing="
              + listing);
      return offer;
    } catch (java.lang.IllegalArgumentException e) {
      Trade lastImpliedTrade = getLastTrade(listing);
      if (lastImpliedTrade != null) {
        DiscreteAmount bestImpliedAskAmount = getLastTrade(listing).getPrice();
        Market market =
            context.getInjector().getInstance(Market.class).findOrCreate(Exchanges.SELF, listing);
        //	long bestImpliedAsk = getLastTrade(listing).getPriceCount();
        DiscreteAmount bestImpliedAskVolumeAmount = getLastTrade(listing).getVolume();
        long bestImpliedAskVolume =
            Math.abs(
                    DiscreteAmount.roundedCountForBasis(
                        bestImpliedAskVolumeAmount.asBigDecimal(), market.getVolumeBasis()))
                * -1;

        long bestImpliedAsk =
            DiscreteAmount.roundedCountForBasis(
                bestImpliedAskAmount.asBigDecimal(), market.getPriceBasis());
        Offer offer =
            new Offer(market, Instant.now(), Instant.now(), bestImpliedAsk, bestImpliedAskVolume);
        log.debug(
            this.getClass().getSimpleName()
                + ":getImpliedBestAskForListing - Calcaulted implied ask="
                + offer.getPrice()
                + ", listing="
                + listing);
        return offer;
      } else {

        DiscreteAmount bestImpliedTradeAmount =
            impliedTradeMatrix.getRate(listing.getBase(), listing.getQuote());
        if (bestImpliedTradeAmount != null) {
          Market market =
              context.getInjector().getInstance(Market.class).findOrCreate(Exchanges.SELF, listing);

          long bestImpliedAsk =
              DiscreteAmount.roundedCountForBasis(
                  bestImpliedTradeAmount.asBigDecimal(), market.getPriceBasis());
          // long bestImpliedAsk = impliedAskMatrix.getRate(listing.getBase(),
          // listing.getQuote()).getCount();

          Offer offer = new Offer(market, Instant.now(), Instant.now(), bestImpliedAsk, 0L);
          log.debug(
              this.getClass().getSimpleName()
                  + ":getImpliedBestAskForListing - Calcaulted implied trade="
                  + offer.getPrice()
                  + ", listing="
                  + listing);
          return offer;
        } else {

          log.debug(
              this.getClass().getSimpleName()
                  + ":getImpliedBestAskForListing - Unable to detreming implied ask "
                  + listing
                  + " from impliedAskMatrix: "
                  + impliedAskMatrix
                  + " or last trade "
                  + lastTradeByListing
                  + " or impliedTradeMatrix"
                  + impliedTradeMatrix);

          return null;
        }
      }
    }
  }

  @Override
  public @Nullable Offer getImpliedBestBidForListing(Listing listing) {
    if (listing == null) return null;
    try {
      DiscreteAmount bestImpliedBidAmount =
          impliedBidMatrix.getRate(listing.getBase(), listing.getQuote());
      Market market =
          context.getInjector().getInstance(Market.class).findOrCreate(Exchanges.SELF, listing);

      long bestImpliedBid =
          DiscreteAmount.roundedCountForBasis(
              bestImpliedBidAmount.asBigDecimal(), market.getPriceBasis());
      // long bestImpliedBid = impliedBidMatrix.getRate(listing.getBase(),
      // listing.getQuote()).getCount();

      Offer offer = new Offer(market, Instant.now(), Instant.now(), bestImpliedBid, 0L);
      log.debug(
          this.getClass().getSimpleName()
              + ":getImpliedBestBidForListing - Calcaulted implied bid="
              + offer.getPrice()
              + ", listing="
              + listing);
      return offer;

    } catch (java.lang.IllegalArgumentException e) {
      Trade lastImpliedTrade = getLastTrade(listing);
      if (lastImpliedTrade != null) {
        DiscreteAmount bestImpliedBidAmount = getLastTrade(listing).getPrice();
        Market market =
            context.getInjector().getInstance(Market.class).findOrCreate(Exchanges.SELF, listing);
        //	long bestImpliedBid = getLastTrade(listing).getPriceCount();
        DiscreteAmount bestImpliedBidVolumeAmount = getLastTrade(listing).getVolume();
        long bestImpliedBidVolume =
            DiscreteAmount.roundedCountForBasis(
                bestImpliedBidVolumeAmount.asBigDecimal(), market.getVolumeBasis());

        long bestImpliedBid =
            Math.abs(
                DiscreteAmount.roundedCountForBasis(
                    bestImpliedBidAmount.asBigDecimal(), market.getPriceBasis()));
        Offer offer =
            new Offer(market, Instant.now(), Instant.now(), bestImpliedBid, bestImpliedBidVolume);
        log.debug(
            this.getClass().getSimpleName()
                + ":getImpliedBestBidForListing - Calcaulted implied bid="
                + offer.getPrice()
                + ", listing="
                + listing);

        return offer;
      } else {

        DiscreteAmount bestImpliedTradeAmount =
            impliedTradeMatrix.getRate(listing.getBase(), listing.getQuote());
        if (bestImpliedTradeAmount != null) {
          Market market =
              context.getInjector().getInstance(Market.class).findOrCreate(Exchanges.SELF, listing);

          long bestImpliedAsk =
              DiscreteAmount.roundedCountForBasis(
                  bestImpliedTradeAmount.asBigDecimal(), market.getPriceBasis());
          // long bestImpliedAsk = impliedAskMatrix.getRate(listing.getBase(),
          // listing.getQuote()).getCount();

          Offer offer = new Offer(market, Instant.now(), Instant.now(), bestImpliedAsk, 0L);
          log.debug(
              this.getClass().getSimpleName()
                  + ":getImpliedBestBidForListing - Calcaulted implied trade="
                  + offer.getPrice()
                  + ", listing="
                  + listing);
          return offer;
        } else {

          log.debug(
              this.getClass().getSimpleName()
                  + ":getImpliedBestBidForListing - Unable to detreming implied ask "
                  + listing
                  + " from impliedAskMatrix: "
                  + impliedAskMatrix
                  + " or last trade "
                  + lastTradeByListing
                  + " or impliedTradeMatrix"
                  + impliedTradeMatrix);

          return null;
        }
      }
    }
  }

  @Override
  public @Nullable Offer getLastAskForMarket(Tradeable market) {
    if (market == null) return null;
    Offer bestAsk = null;
    /*
     * if (XchangeData.exists()) { XchangeData xchangeData = context.getInjector().getInstance(XchangeData.class); try {
     * recordBook(xchangeData.getBook(market, market.getExchange())); } catch (Exception e) { // TODO Auto-generated catch block
     * log.error(this.getClass().getSimpleName() + ": getLastAskForMarket - Unable to retrive latest book for market", e); } }
     */
    Book book = lastBookByMarket.get(market.getSymbol());
    if (book != null) bestAsk = book.getBestAsk();
    //noinspection ConstantConditions

    if (bestAsk == null || bestAsk.getVolumeCount() == 0 || bestAsk.getPriceCount() == 0) {
      DiscreteAmount bestImpliedAskAmount = getLastTrade(market).getPrice();
      DiscreteAmount bestImpliedAskVolumeAmount = getLastTrade(market).getVolume();
      long bestImpliedAskVolume =
          Math.abs(
                  DiscreteAmount.roundedCountForBasis(
                      bestImpliedAskVolumeAmount.asBigDecimal(), market.getVolumeBasis()))
              * -1;

      long bestImpliedAsk =
          DiscreteAmount.roundedCountForBasis(
              bestImpliedAskAmount.asBigDecimal(), market.getPriceBasis());
      bestAsk =
          new Offer(market, Instant.now(), Instant.now(), bestImpliedAsk, bestImpliedAskVolume);
    }

    return bestAsk;
  }

  // @Priority(10)

  private void updateMatrix(ListingsMatrix matrix, Asset base, Asset quote, DiscreteAmount rate) {
    try {

      if (seedUSDT
          && (("USD".equals(quote.getSymbol()) && "USDT".equals(base.getSymbol()))
              || ("USD".equals(base.getSymbol()) && "USDT".equals(quote.getSymbol())))) {

        rate =
            new DiscreteAmount(
                DiscreteAmount.roundedCountForBasis(BigDecimal.ONE, quote.getBasis()),
                quote.getBasis());

        matrix.updateRates(base, quote, rate);

        matrix.updateRates(
            quote,
            base,
            new DiscreteAmount(
                DiscreteAmount.roundedCountForBasis(rate.invert().asBigDecimal(), base.getBasis()),
                base.getBasis()));

        log.info("added  USD/USDT to matrix=" + matrix);
      } else {

        matrix.updateRates(base, quote, rate);

        matrix.updateRates(
            quote,
            base,
            new DiscreteAmount(
                DiscreteAmount.roundedCountForBasis(rate.invert().asBigDecimal(), base.getBasis()),
                base.getBasis()));
      }
    } catch (java.lang.IllegalArgumentException e) {
      try {
        matrix.addAsset(base, quote, rate);
        if (seedUSDT && "USDT".equals(quote.getSymbol())) {

          Currency USD = Currency.forSymbol("USD");
          matrix.addAsset(
              USD,
              quote,
              new DiscreteAmount(
                  DiscreteAmount.roundedCountForBasis(BigDecimal.ONE, USD.getBasis()),
                  USD.getBasis()));
          log.info("seeded USD/USDT to matrix=" + matrix);
        }

      } catch (java.lang.IllegalArgumentException e2) {
      }
    }
    try {
      matrix.updateRates(base, quote, rate);

    } catch (java.lang.IllegalArgumentException e) {
      try {
        matrix.addAsset(base, quote, rate);
      } catch (java.lang.IllegalArgumentException e2) {
        log.error("Threw a Execption, full stack trace follows:", e2);
      }
    }
  }

  @When("@Priority(1) @Audit select * from LastBookWindow")
  private void recordBook(Book b) {
    Tradeable market = b.getMarket();
    if (!market.isSynthetic()) {
      Market marketToHandel = (Market) market;

      handleMarket(marketToHandel);

      String listingSymbol = marketToHandel.getListing().getSymbol();
      Book lastBookForListing = lastBookByListing.get(listingSymbol);
      if (lastBookForListing == null || !lastBookForListing.getTime().isAfter(b.getTime()))
        lastBookByListing.put(listingSymbol, b);
      if (b.getBids() != null && !b.getBids().isEmpty())
        updateMatrix(
            impliedBidMatrix, marketToHandel.getBase(), marketToHandel.getQuote(), b.getBidPrice());
      if (b.getAsks() != null && !b.getAsks().isEmpty())
        updateMatrix(
            impliedAskMatrix, marketToHandel.getBase(), marketToHandel.getQuote(), b.getAskPrice());
    }

    String marketSymbol = market.getSymbol();
    Book lastBookForMarket = lastBookByMarket.get(marketSymbol);
    if (lastBookForMarket == null || !lastBookForMarket.getTime().isAfter(b.getTime()))
      lastBookByMarket.put(marketSymbol, b);

    Offer bestBid = b.getBestBid();
    Book lastBestBidBook = bestBidByMarket.get(marketSymbol);
    //noinspection ConstantConditions
    if (bestBid != null
        && (lastBestBidBook == null
            || bestBid.getPrice().compareTo(lastBestBidBook.getBestBid().getPrice()) > 0))
      bestBidByMarket.put(marketSymbol, b);

    Offer bestAsk = b.getBestAsk();
    Book lastBestAskBook = bestAskByMarket.get(marketSymbol);
    //noinspection ConstantConditions
    if (bestAsk != null
        && (lastBestAskBook == null
            || bestAsk.getPrice().compareTo(lastBestAskBook.getBestAsk().getPrice()) < 0))
      bestAskByMarket.put(marketSymbol, b);
  }

  @When("@Priority(1) @Audit select * from LastTradeWindow")
  private void recordTrade(Trade t) {

    Tradeable market = t.getMarket();
    Listing inverseListing = null;
    DiscreteAmount inversePrice = null;
    Market inverseMarket = null;
    Trade it = null;
    if (!market.isSynthetic()) {
      Market marketToHandle = (Market) market;

      handleMarket(marketToHandle);
      String listingSymbol = marketToHandle.getListing().getSymbol();
      Trade lastTradeForListing = lastTradeByListing.get(listingSymbol);
      if (!t.getPrice().isZero()
          && (lastTradeForListing == null
              || (lastTradeForListing != null
                  && !lastTradeForListing.getTime().isAfter(t.getTime())))) {

        lastTradeByListing.put(listingSymbol, t);
        // long impliedPriceCount = impliedTradeMatrix.getRate(listing.getBase(),
        // listing.getQuote()).getCount();

        /*				if (marketToHandle.getListing().getPrompt() == null)
        	inverseListing = Listing
        			.forSymbol(marketToHandle.getListing().getQuote().getSymbol() + "." + marketToHandle.getListing().getBase().getSymbol());

        else
        	inverseListing = Listing.forSymbol(marketToHandle.getListing().getQuote().getSymbol() + "."
        			+ marketToHandle.getListing().getBase().getSymbol() + "." + marketToHandle.getListing().getPrompt().getSymbol());

        inverseMarket = context.getInjector().getInstance(Market.class).findOrCreate(marketToHandle.getExchange(), inverseListing);
        inversePrice = new DiscreteAmount(
        		DiscreteAmount.roundedCountForBasis(t.getPrice().invert().asBigDecimal(), marketToHandle.getListing().getBase().getBasis()),
        		marketToHandle.getListing().getBase().getBasis());
        inversePrice.toIBasis((long) marketToHandle.getListing().getBase().getBasis(), Remainder.ROUND_EVEN);
        it = new Trade(inverseMarket, t.getTimeReceived(), t.getRemoteKey(), inversePrice.getCount(), t.getVolume().getCount());
        lastTradeByListing.put(inverseListing.getSymbol(), it);*/

      }

      updateMatrix(
          impliedTradeMatrix, marketToHandle.getBase(), marketToHandle.getQuote(), t.getPrice());
      log.trace("updated impliedTradeMatrix={} , trade={} ", impliedTradeMatrix, t);
    }

    String marketSymbol = market.getSymbol();
    Trade lastTradeForMarket = lastTradeByMarket.get(marketSymbol);
    if (lastTradeForMarket == null || !lastTradeForMarket.getTime().isAfter(t.getTime())) {
      lastTradeByMarket.put(marketSymbol, t);
      if (inverseMarket != null && it != null) lastTradeByMarket.put(inverseMarket.getSymbol(), it);
    }
  }

  @When("@Priority(1) @Audit select * from LastBarWindow")
  private void recordBar(Bar b) {
    Tradeable market = b.getMarket();
    double interval = b.getInterval();

    if (!market.isSynthetic()) {
      Market marketToHandle = (Market) b.getMarket();
      handleMarket(marketToHandle);

      String listingSymbol = marketToHandle.getListing().getSymbol();
      Bar lastBarForListing =
          lastBarByListing.get(listingSymbol) == null
                  || lastBarByListing.get(listingSymbol).isEmpty()
              ? null
              : lastBarByListing.get(listingSymbol).get(interval);

      if (lastBarForListing == null || !lastBarForListing.getTime().isAfter(b.getTime())) {
        Map<Double, Bar> barInterval = new ConcurrentHashMap<Double, Bar>();
        if (lastBarByListing.get(listingSymbol) == null) {
          barInterval.put(interval, b);

          lastBarByListing.put(listingSymbol, barInterval);
        } else lastBarByListing.get(listingSymbol).put(interval, b);
      }
    }

    String marketSymbol = market.getSymbol();
    Bar lastBarForMarket =
        lastBarByMarket.get(marketSymbol) == null || lastBarByMarket.get(marketSymbol).isEmpty()
            ? null
            : lastBarByMarket.get(marketSymbol).get(interval);

    // Bar lastBarForMarket = lastBarByMarket.get(marketSymbol).get(interval);
    if (lastBarForMarket == null || !lastBarForMarket.getTime().isAfter(b.getTime())) {

      Map<Double, Bar> barInterval = new ConcurrentHashMap<Double, Bar>();
      if (lastBarByMarket.get(marketSymbol) == null) {
        barInterval.put(interval, b);

        lastBarByMarket.put(marketSymbol, barInterval);
      } else lastBarByMarket.get(marketSymbol).put(interval, b);
    }
  }

  private void handleMarket(Market market) {
    final Listing listing = market.getListing();
    final String listingSymbol = listing.getSymbol();
    boolean found = false;
    Set<Market> markets = marketsByListing.get(listingSymbol);
    if (markets == null) {
      markets = new HashSet<>();
      markets.add(market);
      marketsByListing.put(listingSymbol, markets);
      found = true;
    } else {
      for (Market mappdMarket : markets) {
        if (mappdMarket.equals(market)) found = true;
      }
    }
    if (!found) {
      markets.add(market);
    }
  }

  private final ListingsMatrix impliedBidMatrix = new ListingsMatrix();
  private final ListingsMatrix impliedAskMatrix = new ListingsMatrix();
  private final ListingsMatrix impliedTradeMatrix = new ListingsMatrix();
  protected static Logger log = LoggerFactory.getLogger("org.cryptocoinpartners.quoteService");
  @Inject protected Context context;

  private final Map<String, Trade> lastTradeByListing = new ConcurrentHashMap<>();
  private final Map<String, Book> lastBookByListing = new ConcurrentHashMap<>();
  private final Map<String, Trade> lastTradeByMarket = new ConcurrentHashMap<>();
  private final Map<String, Map<Double, Bar>> lastBarByMarket =
      new ConcurrentHashMap<String, Map<Double, Bar>>();
  private final Map<String, Map<Double, Bar>> lastBarByListing =
      new ConcurrentHashMap<String, Map<Double, Bar>>();

  private final Map<String, Book> lastBookByMarket = new ConcurrentHashMap<>();
  private final Map<String, Book> bestBidByMarket = new ConcurrentHashMap<>();
  private final Map<String, Book> bestAskByMarket = new ConcurrentHashMap<>();
  private final Map<String, Set<Market>> marketsByListing = new ConcurrentHashMap<>();
}

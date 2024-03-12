package org.cryptocoinpartners.schema;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.persistence.Basic;
import javax.persistence.Cacheable;
import javax.persistence.Entity;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.NoResultException;
import javax.persistence.Transient;

import org.cryptocoinpartners.enumeration.ExecutionInstruction;
import org.cryptocoinpartners.enumeration.FeeMethod;
import org.cryptocoinpartners.enumeration.PersistanceAction;
import org.cryptocoinpartners.schema.dao.Dao;
import org.cryptocoinpartners.schema.dao.PromptJpaDao;
import org.cryptocoinpartners.util.EM;
import org.cryptocoinpartners.util.Remainder;

import com.google.inject.Inject;

/** @author Tim Olson */
@Entity
@Cacheable
public class Prompt extends EntityBase {
  @Inject protected static transient PromptJpaDao promptDao;
  private static Map<String, Prompt> promptMap = new HashMap<String, Prompt>();

  public static Prompt forSymbol(String symbol) {
    if (promptMap.isEmpty()) allSymbols();
    if (promptMap.get(symbol) == null) {
      Prompt prompt = EM.queryOne(Prompt.class, "select c from Prompt c where symbol=?1", symbol);
      if (prompt != null) promptMap.put(symbol, prompt);
    }
    return promptMap.get(symbol);
  }

  @Override
  @Transient
  public EntityBase getParent() {

    return null;
  }

  public static Collection<String> allSymbols() {
    if (promptMap.isEmpty()) {
      List<Prompt> prompts = EM.queryList(Prompt.class, "select p from Prompt p");
      for (Prompt prompt : prompts) promptMap.put(prompt.getSymbol(), prompt);
    }
    return promptMap.keySet();
  }

  // JPA
  protected Prompt() {}

  @Transient
  public Amount getMultiplier(Market market, Amount entryPrice, Amount exitPrice) {
    if ("USDT".equals(market.getQuote().getSymbol())) {
      return DecimalAmount.ONE;
    } else return (entryPrice.times(exitPrice, Remainder.ROUND_EVEN)).invert();
  }

  protected synchronized void setSymbol(String symbol) {
    this.symbol = symbol;
  }

  @Basic(optional = false)
  public String getSymbol() {
    return this.symbol;
  }

  protected synchronized void setTickValue(double tickValue) {
    this.tickValue = tickValue;
  }

  @Basic(optional = false)
  public double getTickValue() {
    return this.tickValue;
  }

  protected synchronized void setTickSize(double tickSize) {
    this.tickSize = tickSize;
  }

  @Basic(optional = false)
  public double getTickSize() {
    return this.tickSize;
  }

  protected synchronized void setContractSize(double contractSize) {
    this.contractSize = contractSize;
  }

  @Basic(optional = true)
  private double getContractSize() {
    return this.contractSize;
  }

  @Transient
  public double getContractSize(Market market) {
    if ("USDT".equals(market.getQuote().getSymbol())) {
      if ("BTC".equals(market.getBase().getSymbol())) return 0.01;
      if ("LTC".equals(market.getBase().getSymbol())) return 1;
      if ("SOL".equals(market.getBase().getSymbol())) return 1;
      if ("ETH".equals(market.getBase().getSymbol())) return 0.1;
      if ("DOT".equals(market.getBase().getSymbol())) return 1;
      if ("SNX".equals(market.getBase().getSymbol())) return 1;
      if ("YFI".equals(market.getBase().getSymbol())) return 0.0001;
      if ("SUSHI".equals(market.getBase().getSymbol())) return 1;
      if ("AAVE".equals(market.getBase().getSymbol())) return 0.1;
      if ("TRX".equals(market.getBase().getSymbol())) return 1000;
      if ("DOGE".equals(market.getBase().getSymbol())) return 1000;

      return 1 / this.contractSize;

    } else {
      if (!"BTC".equals(market.getBase().getSymbol())) return this.contractSize * 0.1;
      return this.contractSize;
    }
  }

  protected synchronized void setPriceBasis(double priceBasis) {
    this.priceBasis = priceBasis;
  }

  @Basic(optional = true)
  public double getPriceBasis() {
    return this.priceBasis;
  }

  protected synchronized void setVolumeBasis(double volumeBasis) {
    this.volumeBasis = volumeBasis;
  }

  @Basic(optional = true)
  public double getVolumeBasis() {
    return this.volumeBasis;
  }

  @Basic(optional = true)
  public int getMargin() {
    return this.margin;
  }

  @Basic(optional = true)
  public double getLiquidation() {
    return this.liquidation;
  }

  protected synchronized void setMargin(int margin) {
    this.margin = margin;
  }

  protected synchronized void setLiquidation(double liquidation) {
    this.liquidation = liquidation;
  }

  @ManyToOne(optional = true)
  private FeeMethod marginMethod;

  @ManyToOne(optional = true)
  private FeeMethod marginFeeMethod;

  public FeeMethod getMarginMethod() {
    return marginMethod;
  }

  public FeeMethod getMarginFeeMethod() {
    return marginFeeMethod;
  }

  public FeeMethod getFeeMethod() {
    return feeMethod;
  }

  @Transient
  @Basic(optional = false)
  public double getFeeRate(ExecutionInstruction executionInstruction) {
    if (executionInstruction != null && executionInstruction.equals(ExecutionInstruction.MAKER))
      return makerFeeRate;
    else return takerFeeRate;
  }

  @Basic(optional = false)
  public double getTakerFeeRate() {
    return takerFeeRate;
  }

  @Basic(optional = false)
  public double getMakerFeeRate() {
    return makerFeeRate;
  }

  protected synchronized void setTakerFeeRate(double takerFeeRate) {
    this.takerFeeRate = takerFeeRate;
  }

  protected synchronized void setMakerFeeRate(double makerFeeRate) {
    this.makerFeeRate = makerFeeRate;
  }

  protected synchronized void setMarginMethod(FeeMethod marginMethod) {
    this.marginMethod = marginMethod;
  }

  protected synchronized void setMarginFeeMethod(FeeMethod marginFeeMethod) {
    this.marginFeeMethod = marginFeeMethod;
  }

  protected synchronized void setFeeMethod(FeeMethod feeMethod) {
    this.feeMethod = feeMethod;
  }

  @Nullable
  protected synchronized void setTradedCurrency(Asset tradedCurrency) {
    this.tradedCurrency = tradedCurrency;
  }

  @ManyToOne(optional = true)
  @JoinColumn(name = "tradedCurrency")
  private Asset getTradedCurrency() {
    return this.tradedCurrency;
  }

  @Transient
  public Asset getTradedCurrency(Market market) {
    if (getTradedCurrency() == null) {

      if ("USDT".equals(market.getListing().getQuote().getSymbol())) {
        return market.getListing().getQuote();
      } else return market.getListing().getBase();
    } else return getTradedCurrency();
  }

  // used by Currencies

  static Prompt forSymbolOrCreate(
      String symbol,
      double tickValue,
      double tickSize,
      String currency,
      double volumeBasis,
      double priceBasis,
      int margin,
      FeeMethod marginMethod,
      double makerFeeRate,
      double takerFeeRate,
      FeeMethod feeMethod,
      FeeMethod marginFeeMethod) {
    try {
      return forSymbol(symbol);
    } catch (NoResultException e) {
      Asset tradedCurrency = null;
      if (currency != null) tradedCurrency = Currency.forSymbol(currency);
      final Prompt prompt =
          new Prompt(
              symbol,
              tickValue,
              tickSize,
              tradedCurrency,
              volumeBasis,
              priceBasis,
              margin,
              marginMethod,
              makerFeeRate,
              takerFeeRate,
              feeMethod,
              marginFeeMethod);
      prompt.setRevision(prompt.getRevision() + 1);
      try {
        promptDao.persistEntities(false, prompt);
      } catch (Throwable e1) {
        // TODO Auto-generated catch block
        e1.printStackTrace();
      }
      return prompt;
    }
  }

  static Prompt forSymbolOrCreate(
      String symbol,
      double tickValue,
      double tickSize,
      double volumeBasis,
      double priceBasis,
      int margin,
      FeeMethod marginMethod,
      double makerfeeRate,
      double takerfeeRate,
      FeeMethod feeMethod,
      FeeMethod marginFeeMethod) {
    try {
      return forSymbol(symbol);
    } catch (NoResultException e) {
      final Prompt prompt =
          new Prompt(
              symbol,
              tickValue,
              tickSize,
              volumeBasis,
              priceBasis,
              margin,
              marginMethod,
              makerfeeRate,
              takerfeeRate,
              feeMethod,
              marginFeeMethod);
      prompt.setRevision(prompt.getRevision() + 1);
      try {
        promptDao.persistEntities(false, prompt);
      } catch (Throwable e1) {
        // TODO Auto-generated catch block
        e1.printStackTrace();
      }
      return prompt;
    }
  }

  @ManyToOne(optional = false)
  private FeeMethod feeMethod;

  private Prompt(
      String symbol,
      double tickValue,
      double tickSize,
      Asset tradedCurrency,
      double volumeBasis,
      double priceBasis) {
    this.symbol = symbol;
    this.tickValue = tickValue;
    this.tickSize = tickSize;
    this.contractSize = tickValue / tickSize;
    this.tradedCurrency = tradedCurrency;
    this.volumeBasis = volumeBasis;
    this.priceBasis = priceBasis;
  }

  public Prompt(
      String symbol,
      double tickValue,
      double tickSize,
      Asset tradedCurrency,
      double volumeBasis,
      double priceBasis,
      int margin,
      FeeMethod marginMethod,
      double makerFeeRate,
      double takerFeeRate,
      FeeMethod feeMethod,
      FeeMethod marginFeeMethod) {
    this.symbol = symbol;
    this.tickValue = tickValue;
    this.tickSize = tickSize;
    this.contractSize = tickValue / tickSize;
    this.tradedCurrency = tradedCurrency;
    this.volumeBasis = volumeBasis;
    this.margin = margin;
    this.marginMethod = marginMethod;
    this.marginFeeMethod = marginFeeMethod;
    this.makerFeeRate = makerFeeRate;
    this.takerFeeRate = takerFeeRate;

    this.feeMethod = feeMethod;
    this.priceBasis = priceBasis;
  }

  private Prompt(
      String symbol,
      double tickValue,
      double tickSize,
      double volumeBasis,
      double priceBasis,
      int margin,
      FeeMethod marginMethod,
      double makerfeeRate,
      double takerfeeRate,
      FeeMethod feeMethod,
      FeeMethod marginFeeMethod) {
    this.symbol = symbol;
    this.tickValue = tickValue;
    this.tickSize = tickSize;
    this.contractSize = tickValue / tickSize;
    this.volumeBasis = volumeBasis;
    this.margin = margin;
    this.marginMethod = marginMethod;
    this.marginFeeMethod = marginFeeMethod;
    this.makerFeeRate = makerFeeRate;
    this.takerFeeRate = takerFeeRate;

    this.feeMethod = feeMethod;
    this.priceBasis = priceBasis;
  }

  private String symbol;
  private double tickValue;
  private double tickSize;
  private double contractSize;
  private Asset tradedCurrency;
  private double priceBasis;
  private double volumeBasis;
  private int margin;
  private double liquidation;
  private double makerFeeRate;
  private double takerFeeRate;

  @Override
  public synchronized void persit() {

    this.setPeristanceAction(PersistanceAction.NEW);

    this.setRevision(this.getRevision() + 1);
    try {
      promptDao.persistEntities(false, this);
    } catch (Throwable e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  @Override
  public synchronized EntityBase refresh() {
    return promptDao.refresh(this);
  }

  @Override
  public synchronized void detach() {
    promptDao.detach(this);
  }

  @Override
  @Transient
  public Dao getDao() {
    return promptDao;
  }

  @Override
  @Transient
  public synchronized void setDao(Dao dao) {
    promptDao = (PromptJpaDao) dao;
    // TODO Auto-generated method stub
    //  return null;
  }

  @Override
  public synchronized void merge() {

    this.setPeristanceAction(PersistanceAction.MERGE);

    this.setRevision(this.getRevision() + 1);
    promptDao.merge(this);
  }

  @Override
  public synchronized void delete() {
    // TODO Auto-generated method stub

  }

  @Override
  public synchronized void prePersist() {
    // TODO Auto-generated method stub

  }

  @Override
  public synchronized void postPersist() {
    // TODO Auto-generated method stub

  }

  @Override
  public void persitParents() {
    // TODO Auto-generated method stub

  }
}

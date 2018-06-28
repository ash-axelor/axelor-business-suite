/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2018 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.apps.prestashop.exports.service;

import com.axelor.apps.base.db.AppPrestashop;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.Unit;
import com.axelor.apps.base.db.repo.ProductRepository;
import com.axelor.apps.base.service.CurrencyService;
import com.axelor.apps.base.service.UnitConversionService;
import com.axelor.apps.base.service.administration.AbstractBatch;
import com.axelor.apps.prestashop.entities.Associations.AvailableStocksAssociationElement;
import com.axelor.apps.prestashop.entities.Associations.AvailableStocksAssociationsEntry;
import com.axelor.apps.prestashop.entities.Associations.CategoriesAssociationElement;
import com.axelor.apps.prestashop.entities.PrestashopAvailableStock;
import com.axelor.apps.prestashop.entities.PrestashopImage;
import com.axelor.apps.prestashop.entities.PrestashopProduct;
import com.axelor.apps.prestashop.entities.PrestashopProductCategory;
import com.axelor.apps.prestashop.entities.PrestashopResourceType;
import com.axelor.apps.prestashop.entities.PrestashopTranslatableString;
import com.axelor.apps.prestashop.service.library.PSWebServiceClient;
import com.axelor.apps.prestashop.service.library.PrestaShopWebserviceException;
import com.axelor.apps.stock.db.repo.StockLocationRepository;
import com.axelor.apps.stock.db.repo.StockMoveRepository;
import com.axelor.db.JPA;
import com.axelor.exception.AxelorException;
import com.axelor.exception.service.TraceBackService;
import com.axelor.i18n.I18n;
import com.axelor.meta.MetaFiles;
import com.google.common.base.Objects;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Singleton
public class ExportProductServiceImpl implements ExportProductService {
  private Logger log = LoggerFactory.getLogger(getClass());

  /**
   * Version from which BOOM-5826 is fixed. FIXME Not accurate right now as bug is not fixed in last
   * version so we can just put lastVersion+1 until a fix is released
   */
  private static final String FIX_POSITION_IN_CATEGORY_VERSION = "1.7.3.5";

  private ProductRepository productRepo;
  private UnitConversionService unitConversionService;
  private CurrencyService currencyService;

  @Inject
  public ExportProductServiceImpl(
      ProductRepository productRepo,
      UnitConversionService unitConversionService,
      CurrencyService currencyService) {
    this.productRepo = productRepo;
    this.unitConversionService = unitConversionService;
    this.currencyService = currencyService;
  }

  @Override
  @Transactional
  public void exportProduct(AppPrestashop appConfig, Writer logBuffer)
      throws IOException, PrestaShopWebserviceException {
    int done = 0;
    int errors = 0;

    logBuffer.write(String.format("%n====== PRODUCTS ======%n"));

    if (appConfig.getPrestaShopLengthUnit() == null
        || appConfig.getPrestaShopWeightUnit() == null) {
      logBuffer.write(String.format("[ERROR] Prestashop module isn't fully configured%n"));
      return;
    }

    final StringBuilder filter =
        new StringBuilder(
            "(self.prestaShopVersion is null OR self.prestaShopVersion < self.version)");
    if (appConfig.getExportNonSoldProducts() == Boolean.FALSE) {
      filter.append(" AND (self.sellable = true)");
    }

    final PSWebServiceClient ws =
        new PSWebServiceClient(appConfig.getPrestaShopUrl(), appConfig.getPrestaShopKey());

    final PrestashopProduct defaultProduct = ws.fetchDefault(PrestashopResourceType.PRODUCTS);
    final PrestashopProductCategory remoteRootCategory =
        ws.fetchOne(
            PrestashopResourceType.PRODUCT_CATEGORIES,
            Collections.singletonMap("is_root_category", "1"));

    final List<PrestashopProduct> remoteProducts = ws.fetchAll(PrestashopResourceType.PRODUCTS);
    final Map<Integer, PrestashopProduct> productsById = new HashMap<>();
    final Map<String, PrestashopProduct> productsByReference = new HashMap<>();
    for (PrestashopProduct p : remoteProducts) {
      productsById.put(p.getId(), p);
      productsByReference.put(p.getReference(), p);
    }
    final int language =
        (appConfig.getTextsLanguage().getPrestaShopId() == null
            ? 1
            : appConfig.getTextsLanguage().getPrestaShopId());

    final LocalDate today = LocalDate.now();

    for (Product localProduct : productRepo.all().filter(filter.toString()).fetch()) {
      try {
        final String cleanedReference =
            localProduct
                .getCode()
                .replaceAll("[<>;={}]", ""); // took from Prestashop's ValidateCore::isReference
        logBuffer.write(
            String.format(
                "Exporting product %s (%s/%s) – ",
                localProduct.getName(), localProduct.getCode(), cleanedReference));

        if (localProduct.getParentProduct() != null) {
          logBuffer.write(
              String.format(
                  "[ERROR] Product is a variant, these are not handled right now, skipping%n"));
          continue;
        } else if (localProduct.getProductVariantConfig() != null) {
          logBuffer.write(
              String.format(
                  "[ERROR] Product has variants, which are not handled right now, skipping%n"));
          continue;
        } else if (localProduct.getIsPack() == Boolean.TRUE) {
          // FIXME fairly easy to fix through product_bundle association + set type to pack
          logBuffer.write(
              String.format(
                  "[ERROR] Product is a pack, these are not handled right now, skipping%n"));
          continue;
        }

        PrestashopProduct remoteProduct;
        if (localProduct.getPrestaShopId() != null) {
          logBuffer.write("prestashop id=" + localProduct.getPrestaShopId());
          remoteProduct = productsById.get(localProduct.getPrestaShopId());
          if (remoteProduct == null) {
            logBuffer.write(String.format(" [ERROR] Not found remotely%n"));
            log.error(
                "Unable to fetch remote product #{} ({}), something's probably very wrong, skipping",
                localProduct.getPrestaShopId(),
                localProduct.getCode());
            ++errors;
            continue;
          } else if (cleanedReference.equals(remoteProduct.getReference()) == false) {
            log.error(
                "Remote product #{} has not the same reference as the local one ({} vs {}), skipping",
                localProduct.getPrestaShopId(),
                remoteProduct.getReference(),
                cleanedReference);
            logBuffer.write(
                String.format(
                    " [ERROR] reference mismatch: %s vs %s%n",
                    remoteProduct.getReference(), cleanedReference));
            ++errors;
            continue;
          }
        } else {
          remoteProduct = productsByReference.get(cleanedReference);
          if (remoteProduct == null) {
            logBuffer.write("no ID and reference not found, creating");
            remoteProduct = new PrestashopProduct();
            remoteProduct.setReference(cleanedReference);
            PrestashopTranslatableString str = defaultProduct.getName().clone();
            str.clearTranslations(localProduct.getName());
            remoteProduct.setName(str);

            str = defaultProduct.getDescription().clone();
            str.clearTranslations(localProduct.getDescription());
            remoteProduct.setDescription(str);

            str = defaultProduct.getLinkRewrite().clone();
            // Normalization taken from PrestaShop's JavaScript str2url function
            str.clearTranslations(
                Normalizer.normalize(
                        String.format("%s-%s", localProduct.getCode(), localProduct.getName()),
                        Normalizer.Form.NFKD)
                    .replaceAll("[\\p{InCombiningDiacriticalMarks}]", "")
                    .toLowerCase()
                    .replaceAll("[^a-z0-9\\s\\'\\:/\\[\\]\\-]", "")
                    .replaceAll("[\\s\\'\\:/\\[\\]-]+", " ")
                    .replaceAll(" ", "-"));
            // TODO Should we update when product name changes?
            remoteProduct.setLinkRewrite(str);
            remoteProduct.setPositionInCategory(0);
          } else {
            logBuffer.write(
                String.format("found remotely using its reference %s", cleanedReference));
          }
        }

        if (remoteProduct.getId() == null
            || appConfig.getPrestaShopMasterForProducts() == Boolean.FALSE) {
          // Here comes the real fun…
          if (localProduct.getProductCategory() != null
              && localProduct.getProductCategory().getPrestaShopId() != null) {
            remoteProduct.setDefaultCategoryId(localProduct.getProductCategory().getPrestaShopId());
          } else {
            remoteProduct.setDefaultCategoryId(remoteRootCategory.getId());
          }

          final int defaultCategoryId = remoteProduct.getDefaultCategoryId();
          if (remoteProduct
                  .getAssociations()
                  .getCategories()
                  .getAssociations()
                  .stream()
                  .anyMatch(c -> c.getId() == defaultCategoryId)
              == false) {
            CategoriesAssociationElement e = new CategoriesAssociationElement();
            e.setId(defaultCategoryId);
            remoteProduct.getAssociations().getCategories().getAssociations().add(e);
          }

          if (localProduct.getSalePrice() != null) {
            if (localProduct.getSaleCurrency() != null) {
              try {
                remoteProduct.setPrice(
                    currencyService
                        .getAmountCurrencyConvertedAtDate(
                            localProduct.getSaleCurrency(),
                            appConfig.getPrestaShopCurrency(),
                            localProduct.getSalePrice(),
                            today)
                        .setScale(appConfig.getExportPriceScale(), BigDecimal.ROUND_HALF_UP));
              } catch (AxelorException e) {
                logBuffer.write(
                    " [WARNING] Unable to convert sale price, check your currency convsersion rates");
              }
            } else {
              remoteProduct.setPrice(
                  localProduct
                      .getSalePrice()
                      .setScale(appConfig.getExportPriceScale(), BigDecimal.ROUND_HALF_UP));
            }
          }
          if (localProduct.getPurchasePrice() != null) {
            if (localProduct.getPurchaseCurrency() != null) {
              try {
                remoteProduct.setWholesalePrice(
                    currencyService
                        .getAmountCurrencyConvertedAtDate(
                            localProduct.getPurchaseCurrency(),
                            appConfig.getPrestaShopCurrency(),
                            localProduct.getPurchasePrice(),
                            today)
                        .setScale(appConfig.getExportPriceScale(), BigDecimal.ROUND_HALF_UP));
              } catch (AxelorException e) {
                logBuffer.write(
                    " [WARNING] Unable to convert purchase price, check your currency convsersion rates");
              }
            } else {
              remoteProduct.setWholesalePrice(
                  localProduct
                      .getPurchasePrice()
                      .setScale(appConfig.getExportPriceScale(), BigDecimal.ROUND_HALF_UP));
            }
          }
          if (localProduct.getLengthUnit() != null) {
            remoteProduct.setWidth(
                convert(
                    appConfig.getPrestaShopLengthUnit(),
                    localProduct.getLengthUnit(),
                    localProduct.getWidth()));
            remoteProduct.setHeight(
                convert(
                    appConfig.getPrestaShopLengthUnit(),
                    localProduct.getLengthUnit(),
                    localProduct.getHeight()));
            remoteProduct.setDepth(
                convert(
                    appConfig.getPrestaShopLengthUnit(),
                    localProduct.getLengthUnit(),
                    localProduct.getLength()));
          } else {
            // assume homogeneous units
            remoteProduct.setWidth(localProduct.getWidth());
            remoteProduct.setHeight(localProduct.getHeight());
            remoteProduct.setDepth(localProduct.getLength());
          }
          BigDecimal weight =
              localProduct.getGrossWeight() == null
                  ? localProduct.getNetWeight()
                  : localProduct.getGrossWeight();
          if (localProduct.getWeightUnit() != null) {
            remoteProduct.setWeight(
                unitConversionService.convert(
                    appConfig.getPrestaShopWeightUnit(), localProduct.getWeightUnit(), weight));
          } else {
            remoteProduct.setWeight(weight);
          }

          remoteProduct.getName().setTranslation(language, localProduct.getName());
          remoteProduct.getDescription().setTranslation(language, localProduct.getDescription());
          remoteProduct.setTaxRulesGroupId(
              1); // FIXME Need to have a mapping and use getAccountManagementList
          remoteProduct.setEan13(localProduct.getEan13());
          if (localProduct.getSalesUnit() != null) {
            remoteProduct.setUnity(localProduct.getSalesUnit().getLabelToPrinting());
          } else if (localProduct.getUnit() != null) {
            remoteProduct.setUnity(localProduct.getUnit().getLabelToPrinting());
          }
          remoteProduct.setVirtual(
              ProductRepository.PRODUCT_TYPE_SERVICE.equals(localProduct.getProductTypeSelect()));
          // TODO Should we handle supplier?

          remoteProduct.setUpdateDate(LocalDateTime.now());
          if (ws.compareVersion(FIX_POSITION_IN_CATEGORY_VERSION) < 0) {
            // Workaround Prestashop bug BOOM-5826 (position in category handling in prestashop's
            // webservices is a joke). Trade-off is that we shuffle categories on each update…
            remoteProduct.setPositionInCategory(0);
          }
          remoteProduct = ws.save(PrestashopResourceType.PRODUCTS, remoteProduct);
          productsById.put(remoteProduct.getId(), remoteProduct);

          if (localProduct.getPicture() != null) {
            // FIXME If the unique modification we made to product is its picture, product version
            // isn't updated, so we've to split this to another batch
            if (Objects.equal(
                        localProduct.getPicture().getVersion(),
                        localProduct.getPrestaShopImageVersion())
                    == false
                || Objects.equal(
                        localProduct.getPicture().getId(), localProduct.getPrestaShopImageId())
                    == false) {
              logBuffer.write(" – no image stored or image updated, adding a new one");
              try (InputStream is =
                  new FileInputStream(MetaFiles.getPath(localProduct.getPicture()).toFile())) {
                PrestashopImage image =
                    ws.addImage(PrestashopResourceType.PRODUCTS, remoteProduct, is);
                remoteProduct.setDefaultImageId(image.getId());
                localProduct.setPrestaShopImageId(localProduct.getPicture().getId());
                localProduct.setPrestaShopImageVersion(localProduct.getPicture().getVersion());
                remoteProduct = ws.save(PrestashopResourceType.PRODUCTS, remoteProduct);
              }
            }
          }

          localProduct.setPrestaShopId(remoteProduct.getId());
          localProduct.setPrestaShopVersion(localProduct.getVersion() + 1);
        } else {
          logBuffer.write(
              "remote product exists and PrestaShop is master for products, leaving untouched");
        }
        logBuffer.write(String.format(" [SUCCESS]%n"));
        ++done;
      } catch (AxelorException | PrestaShopWebserviceException e) {
        logBuffer.write(
            String.format(
                " [ERROR] %s (full trace is in application logs)%n", e.getLocalizedMessage()));
        log.error(
            String.format(
                "Exception while synchronizing product #%d (%s)",
                localProduct.getId(), localProduct.getName()),
            e);
        ++errors;
      }
    }

    exportStocks(ws, productsById, logBuffer);
    exportPictures(ws, productsById, logBuffer);

    logBuffer.write(
        String.format("%n=== END OF PRODUCTS EXPORT, done: %d, errors: %d ===%n", done, errors));
  }

  private void exportStocks(
      final PSWebServiceClient ws,
      final Map<Integer, PrestashopProduct> productsById,
      Writer logBuffer)
      throws IOException {
    logBuffer.write(String.format("%n== STOCKS EXPORT ==%n"));

    @SuppressWarnings("unchecked")
    final List<Object[]> stocks =
        JPA.em()
            .createQuery(
                "SELECT product, "
                    + "("
                    + "SELECT COALESCE(SUM(CASE WHEN fromLocation.typeSelect = :virtualLocation THEN line.realQty ELSE -line.realQty END), 0) "
                    + "FROM StockMoveLine line "
                    + "JOIN line.stockMove move "
                    + "JOIN move.fromStockLocation fromLocation "
                    + "JOIN move.toStockLocation toLocation "
                    + "WHERE line.product = product "
                    + "AND move.statusSelect != :canceledStatus "
                    + "AND (fromLocation.typeSelect != :virtualLocation OR toLocation.typeSelect != :virtualLocation) "
                    + "AND (fromLocation.typeSelect = :virtualLocation OR toLocation.typeSelect = :virtualLocation) "
                    + ")"
                    + "FROM Product product "
                    + "LEFT JOIN product.stockLocationLines line "
                    + "WHERE product.prestaShopId is not null "
                    + "GROUP BY product")
            .setParameter("canceledStatus", StockMoveRepository.STATUS_CANCELED)
            .setParameter("virtualLocation", StockLocationRepository.TYPE_VIRTUAL)
            .getResultList();
    for (Object[] row : stocks) {
      try {
        final Product localProduct = (Product) row[0];
        final int currentStock = ((BigDecimal) row[1]).intValue();
        logBuffer.write(String.format("Updating stock for %s", localProduct.getCode()));
        PrestashopProduct remoteProduct = productsById.get(localProduct.getPrestaShopId());
        if (remoteProduct == null) {
          logBuffer.write(
              String.format(
                  " [ERROR] id %d not found on PrestaShop, skipping%n",
                  localProduct.getPrestaShopId()));
          continue;
        }

        AvailableStocksAssociationsEntry availableStocks =
            remoteProduct.getAssociations().getAvailableStocks();
        if (availableStocks == null || availableStocks.getStock().size() == 0) {
          logBuffer.write(" [WARNING] No stock for this product, skipping stock update%n");
        } else if (availableStocks.getStock().size() > 1
            || Objects.equal(availableStocks.getStock().get(0).getProductAttributeId(), 0)
                == false) {
          logBuffer.write(" [WARNING] Remote product appears to have variants, skipping%n");
        } else {
          AvailableStocksAssociationElement availableStockRef = availableStocks.getStock().get(0);
          PrestashopAvailableStock availableStock =
              ws.fetch(PrestashopResourceType.STOCK_AVAILABLES, availableStockRef.getId());
          if (availableStock.isDependsOnStock()) {
            logBuffer.write(
                " [WARNING] Remote product uses advanced stock management features, not updating stock%n");
          } else if (currentStock != availableStock.getQuantity()) {
            availableStock.setQuantity(currentStock);
            ws.save(PrestashopResourceType.STOCK_AVAILABLES, availableStock);
            logBuffer.write(String.format(", setting stock to %d [SUCCESS]%n", currentStock));
          } else {
            logBuffer.write(String.format(", nothing to do [SUCCESS]%n"));
          }
        }
      } catch (PrestaShopWebserviceException e) {
        logBuffer.write(String.format(" [ERROR] exception occured: %s%n", e.getMessage()));
        TraceBackService.trace(
            e, I18n.get("Prestashop stocks export"), AbstractBatch.getCurrentBatchId());
      }
    }

    logBuffer.write(String.format("== END OF STOCKS EXPORT ==%n"));
  }

  /** Export all pictures that have been modified */
  private void exportPictures(
      final PSWebServiceClient ws,
      final Map<Integer, PrestashopProduct> productsById,
      Writer logBuffer)
      throws IOException {
    logBuffer.write(String.format("%n== PICTURES EXPORT ==%n"));

    final List<Product> products =
        productRepo
            .all()
            .filter(
                "self.prestaShopId is not null and self.picture is not null and "
                    + "(self.prestaShopImageVersion is null "
                    + "OR self.prestaShopImageId is null "
                    + "OR self.picture.version != self.prestaShopImageVersion "
                    + "OR self.picture.id != self.prestaShopImageId)")
            .order("code")
            .fetch();

    for (Product localProduct : products) {
      try {
        logBuffer.write(String.format("Updating picture for %s", localProduct.getCode()));
        final PrestashopProduct remoteProduct = productsById.get(localProduct.getPrestaShopId());
        if (remoteProduct == null) {
          logBuffer.write(
              String.format(
                  " [ERROR] id %d not found on PrestaShop, skipping%n",
                  localProduct.getPrestaShopId()));
          continue;
        }

        try (InputStream is =
            new FileInputStream(MetaFiles.getPath(localProduct.getPicture()).toFile())) {
          PrestashopImage image = ws.addImage(PrestashopResourceType.PRODUCTS, remoteProduct, is);
          remoteProduct.setDefaultImageId(image.getId());
          localProduct.setPrestaShopImageId(localProduct.getPicture().getId());
          localProduct.setPrestaShopImageVersion(localProduct.getPicture().getVersion());
          ws.save(PrestashopResourceType.PRODUCTS, remoteProduct);
          logBuffer.write(String.format(" [SUCCESS]%n"));
        }
      } catch (PrestaShopWebserviceException e) {
        logBuffer.write(String.format(" [ERROR] exception occured: %s%n", e.getMessage()));
        TraceBackService.trace(
            e, I18n.get("Prestashop product images export"), AbstractBatch.getCurrentBatchId());
      }
    }
    logBuffer.write(String.format("%n== END OF PICTURES EXPORT ==%n"));
  }

  // Null-safe version of UnitConversionService::Convert (feel free to integrate to base method).
  private BigDecimal convert(Unit from, Unit to, BigDecimal value) throws AxelorException {
    if (value == null) return null;
    return unitConversionService.convert(from, to, value);
  }
}

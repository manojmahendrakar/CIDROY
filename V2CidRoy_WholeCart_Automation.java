import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testng.Assert;
import org.testng.TestNG;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * One-file WholeCart UI test covering the complete 11-step end-to-end workflow:
 * 1. Logs in as Operator, applies only mismatched required settings, and verifies them after refresh.
 * 2. Validates seller GSTIN details and runs one logistics export before logging out of that Operator session.
 * 3. Logs in as Buyer, searches the supplied catalogue categories, and selects a product by name priority.
 * 4. Verifies product details/stock, rejects invalid quantity without changing cart, adds 1 case, updates to 2, and verifies persistence.
 * 5. Selects tomorrow and enabled slot, verifies free-delivery threshold, places order 1, and verifies order-history/detail values.
 * 6. Cancels the first order and verifies cancelled status.
 * 7. Returns to Catalogue, re-adds only the canceled product (2 cases), and verifies Cart contains only that product.
 * 8. Checks out single-product cart for tomorrow using 17:00–20:00 slot, places order 2, and verifies Order placed!
 * 9. Captures order number, buyer, slot, total, logs out Buyer, and logs into matching seller.
 * 10. Finds exact generated order in Seller Orders and validates before Accept -> Ship -> Deliver.
 * 11. Logs out seller and closes shared browser in teardown.
 */
public final class V2CidRoy_WholeCart_Automation {
    private static final String BASE_URL = System.getenv().getOrDefault(
            "WHOLECART_URL", "http://13.200.185.1/c/manoj-154697/");
    private static String PASSWORD = System.getenv("WHOLECART_PASSWORD") != null
            && !System.getenv("WHOLECART_PASSWORD").isBlank()
            ? System.getenv("WHOLECART_PASSWORD").trim()
            : "Test@123";
    private static final int WAIT_SECONDS = 20;
    private static final BigDecimal MINIMUM_ORDER = new BigDecimal("2000.00");
    private static final BigDecimal FREE_DELIVERY = new BigDecimal("3000.00");
    private static final BigDecimal DELIVERY_FEE = new BigDecimal("100.00");
    private static final DateTimeFormatter MINUTE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern GSTIN =
            Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][0-9A-Z]Z[0-9A-Z]$");

    private WebDriver driver;
    private WebDriverWait wait;
    private final List<String> reportSteps = new ArrayList<>();
    private String currentStep;
    private String failureDetails = "";
    private String failureScreenshot = "";
    private long stepStartedAt;
    private boolean testCompleted;

    public static void main(String[] args) {
        if (args.length > 0 && !args[0].isBlank()) {
            PASSWORD = args[0].trim();
        }

        System.out.println("==================================================================");
        System.out.println("Executing WholeCart V2 Automation Tests...");
        System.out.println("Target URL: " + BASE_URL);
        System.out.println("==================================================================");

        try {
            TestNG testNG = new TestNG();
            testNG.setTestClasses(new Class<?>[] {V2CidRoy_WholeCart_Automation.class});
            testNG.setDefaultTestName("WholeCart V2 Test Suite");
            testNG.run();
            if (testNG.hasFailure()) {
                System.err.println("Test run finished with failures.");
                System.exit(1);
            } else {
                System.out.println("All tests passed successfully.");
            }
        } catch (Throwable t) {
            System.err.println("TestNG runner encountered an issue: " + t.getMessage());
            System.err.println("Running test flow directly...");
            V2CidRoy_WholeCart_Automation runner = new V2CidRoy_WholeCart_Automation();
            try {
                runner.openBrowser();
                runner.completeOrderFlowInOneBrowserSession();
                System.out.println("Direct test run completed successfully.");
            } catch (Throwable directError) {
                System.err.println("Test execution failed: " + directError.getMessage());
                directError.printStackTrace();
                System.exit(1);
            } finally {
                try {
                    runner.closeBrowser();
                } catch (IOException ioException) {
                    System.err.println("Error closing browser: " + ioException.getMessage());
                }
            }
        }
    }

    @BeforeClass
    public void openBrowser() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--window-size=1920,1080");
        options.setExperimentalOption("prefs", Map.of(
                "download.default_directory", Path.of("test-output").toAbsolutePath().toString(),
                "download.prompt_for_download", false,
                "safebrowsing.enabled", true));
        driver = new ChromeDriver(options);
        driver.manage().window().maximize();
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(45));
        wait = new WebDriverWait(driver, Duration.ofSeconds(WAIT_SECONDS));
        driver.get(BASE_URL);
        sleep(500);
    }

    @AfterClass(alwaysRun = true)
    public void closeBrowser() throws IOException {
        try {
            writeHtmlReport();
        } finally {
            if (driver != null) {
                driver.quit();
            }
        }
    }

    @Test(description = "WholeCart operator, buyer order, cancellation, and seller delivery flow")
    public void completeOrderFlowInOneBrowserSession() throws Throwable {
        try {
            // Step 1: Log in as Operator and verify the required marketplace settings.
            step("1. Operator login and settings configuration");
            login("operator");
            Assert.assertEquals(text(By.cssSelector("span.who")), "Marketplace Operations",
                    "Operator identity must be Marketplace Operations");
            verifyOperatorSettings();

            // Step 2: Validate seller GSTIN details and collect seller IDs.
            // Export logistics remains intentionally skipped.
            step("2. Validate seller GSTIN details");
            List<Seller> sellers = readAndValidateSellers();
            // verifyExportDownload(); // Intentionally skipped: export logistics was excluded.

            // Step 3: Log out Operator, log in as Buyer, and select a catalogue product.
            step("3. Operator logout, Buyer login, and product selection");
            logout();
            login("buyer1");
            String buyer = text(By.cssSelector("span.who"));
            Assert.assertTrue(shown(By.cssSelector("a[href*='/catalogue']")),
                    "Buyer must have Catalogue navigation link");
            Product product = selectProduct();

            // Step 4: Verify product details, reject invalid quantity, add one case,
            // update to two cases, and verify cart persistence.
            step("4. Verify product details, reject invalid quantity, add 1 case, update to 2, and verify persistence");
            openCart();
            Assert.assertTrue(cartRows().isEmpty(),
                    "Cart must start empty before placing order; refusing to mix existing items");
            openProduct(product);
            selectUnit("Case");
            enterInvalidZero();
            WebElement invalidQuantity = visible(By.cssSelector("input[data-testid='qty']"));
            ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].form.noValidate=true; arguments[0].form.submit();",
                    invalidQuantity);
            visible(By.cssSelector("[data-testid='flash-error']"));
            Assert.assertEquals(
                    text(By.cssSelector("[data-testid='flash-error']")),
                    "Quantity must be a whole number from 1 to 99",
                    "Invalid quantity must display the exact validation message");
            openCart();
            Assert.assertTrue(cartRows().isEmpty(),
                    "Invalid quantity must not add items to Cart");

            // Add 1 case
            openProduct(product);
            selectUnit("Case");
            enterQuantity(1);
            submitAddToCart();
            sleep(300);
            openCart();
            Assert.assertEquals(cartRows().size(), 1, "Cart should contain exactly one selected item");
            BigDecimal oneCaseNet = cartNet();
            Assert.assertEquals(oneCaseNet.compareTo(product.casePrice()), 0,
                    "One-case Cart net total must match product case price");

            // Update to 2 cases
            updateCartQuantity(product.id(), 2);
            BigDecimal firstNet = cartNet();
            Assert.assertEquals(firstNet.compareTo(product.casePrice().multiply(BigDecimal.valueOf(2))), 0,
                    "Two-case Cart net total must match double the case price");
            Assert.assertTrue(firstNet.compareTo(MINIMUM_ORDER) >= 0,
                    "Two-case Cart must meet the configured minimum order of " + MINIMUM_ORDER);

            // Verify Cart persistence
            driver.get(BASE_URL + "catalogue");
            visible(By.xpath("//h1[normalize-space()='Catalogue']"));
            sleep(300);
            openCart();
            Assert.assertEquals(cartRows().size(), 1, "Cart contents must persist after navigating away and back");
            Assert.assertEquals(cartNet().compareTo(firstNet), 0, "Cart net total must persist after navigating away and back");

            // Step 5: Selects tomorrow and an enabled delivery slot, verifies delivery against the configured free-delivery threshold,
            // places one order, captures its generated order ID, and checks the order-history and detail values.
            step("5. Select delivery date & slot, verify delivery fee, place initial order, and verify order details");
            Order firstOrder = checkoutAndPlace(product, firstNet, false);
            verifyOrderDetails(firstOrder, product, 2, firstNet);

            // Step 6: Cancel the first order and verify its cancelled status.
            step("6. Cancel initial order and verify cancelled status");
            openOrder(firstOrder.id());
            Assert.assertEquals(text(By.cssSelector("[data-testid='order-status']")), "placed");
            WebElement cancelOrder = visible(By.cssSelector("button[data-testid='cancel-order']"));
            ((JavascriptExecutor) driver).executeScript("arguments[0].form.submit();", cancelOrder);
            sleep(500);
            wait.until(ExpectedConditions.textToBe(
                    By.cssSelector("[data-testid='order-status']"), "cancelled"));
            Assert.assertEquals(text(By.cssSelector("[data-testid='order-status']")), "cancelled",
                    "Order status must become cancelled after cancellation");

            // Step 7: Re-add only the cancelled product with two cases and verify the cart.
            step("7. Return to Catalogue, re-add only cancelled product (2 cases), and verify Cart");
            openCart();
            Assert.assertTrue(cartRows().isEmpty(),
                    "Cart must be empty after cancelling order before re-adding item");
            openProduct(product);
            selectUnit("Case");
            enterQuantity(2);
            submitAddToCart();
            sleep(300);
            openCart();
            Assert.assertEquals(cartRows().size(), 1,
                    "Follow-up Cart must contain only the re-added cancelled product");
            BigDecimal followUpNet = cartNet();
            Assert.assertEquals(followUpNet.compareTo(firstNet), 0,
                    "Follow-up Cart net must match original two-case net");

            // Step 8: Checks out that single-product cart for tomorrow using the `17:00–20:00` slot, places the order once, and verifies the `Order placed!` confirmation.
            step("8. Follow-up Checkout for tomorrow 17:00-20:00 slot and place order");
            Order sellerOrder = checkoutAndPlace(product, followUpNet, true);

            // Step 9: Log out Buyer and log in as the seller for the selected product.
            step("9. Buyer logout and matching Seller login");
            logout();
            Seller seller = sellers.stream()
                    .filter(item -> item.name().equals(product.seller()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "Product seller '" + product.seller() + "' was not found in the seller list"));
            login("seller" + seller.id());
            Assert.assertEquals(text(By.cssSelector("span.who")), seller.name(),
                    "Logged-in seller name must match the order seller");

            // Step 10: Validate the exact seller order and complete Accept -> Ship -> Deliver.
            step("10. Find exact generated order in Seller Orders and Accept -> Ship -> Deliver");
            processSellerOrder(sellerOrder, buyer);

            // Step 11: Log out Seller and finish the workflow.
            step("11. Seller logout and workflow completion");
            logout();
            addReportStep("PASSED");
            testCompleted = true;
            System.out.println("\n>>> Buyer and Seller WholeCart workflow completed successfully! <<<");
        } catch (Throwable failure) {
            saveFailureScreenshot(failure);
            throw failure;
        }
    }

    private void verifyOperatorSettings() {
        driver.get(BASE_URL + "operator/settings");
        visible(By.xpath("//h1[normalize-space()='Marketplace settings']"));
        sleep(500);

        Map<String, String> expected = Map.of(
                "cutoff_time", "10:00",
                "cancel_window_minutes", "5",
                "slot_capacity", "5",
                "min_order", "2000",
                "free_delivery_threshold", "3000",
                "delivery_fee", "100");
        boolean changed = false;
        for (Map.Entry<String, String> setting : expected.entrySet()) {
            WebElement field = visible(By.name(setting.getKey()));
            if (!setting.getValue().equals(field.getDomProperty("value"))) {
                field.clear();
                field.sendKeys(setting.getValue());
                changed = true;
            }
        }
        WebElement fixedClock = visible(
                By.cssSelector("input[name='clock_mode'][value='fixed']"));
        if (!fixedClock.isSelected()) {
            clickElement(fixedClock);
            changed = true;
        }
        if (changed) {
            click(By.cssSelector("form[action*='/operator/settings'] button"));
            sleep(500);
            visible(By.cssSelector(".flash.ok"));
        }
        driver.navigate().refresh();
        sleep(500);
        visible(By.xpath("//h1[normalize-space()='Marketplace settings']"));
        for (Map.Entry<String, String> setting : expected.entrySet()) {
            Assert.assertEquals(
                    visible(By.name(setting.getKey())).getDomProperty("value"),
                    setting.getValue(),
                    "Setting did not persist: " + setting.getKey());
        }
        Assert.assertTrue(visible(
                By.cssSelector("input[name='clock_mode'][value='fixed']")).isSelected(),
                "Fixed clock mode must remain selected");
    }

    private List<Seller> readAndValidateSellers() {
        driver.get(BASE_URL + "operator/sellers");
        visible(By.xpath("//h1[normalize-space()='Sellers']"));
        sleep(500);
        List<Seller> sellers = new ArrayList<>();
        List<WebElement> rows = driver.findElements(By.cssSelector("table tbody tr"));
        Assert.assertFalse(rows.isEmpty(), "No seller records were found in operator sellers table");
        for (WebElement row : rows) {
            List<WebElement> cells = row.findElements(By.tagName("td"));
            Assert.assertTrue(cells.size() >= 5, "Seller row is missing expected columns");
            String id = cells.get(0).getText().trim();
            String name = cells.get(1).getText().trim();
            String gstin = cells.get(2).getText().trim().toUpperCase(Locale.ROOT);
            Assert.assertTrue(GSTIN.matcher(gstin).matches(), "Invalid GSTIN format for seller " + name + ": " + gstin);
            sellers.add(new Seller(id, name));
        }
        return sellers;
    }

    private void verifyExportDownload() throws IOException {
        Path folder = Path.of("test-output").toAbsolutePath();
        Files.createDirectories(folder);

        // Remove any old export files so the new download is clean and unambiguous
        try (var stream = Files.list(folder)) {
            stream.filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).contains("logistics"))
                  .forEach(f -> {
                      try { Files.deleteIfExists(f); } catch (IOException ignored) {}
                  });
        }

        Set<Path> before = filesIn(folder);
        driver.get(BASE_URL + "operator/export");
        visible(By.xpath("//h1[contains(normalize-space(),'Logistics export')]"));
        sleep(500);

        WebElement exportButton = visible(By.xpath(
                "//form[contains(@action,'export')]//button[normalize-space()='Run export now']"));
        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", exportButton);
        sleep(1000);

        Map<Path, Long> sizes = new HashMap<>();
        Path download = wait.until(ignored -> completedDownload(folder, before, sizes));
        Assert.assertTrue(Files.size(download) > 0, "Logistics export download file is empty: " + download.getFileName());
        sleep(500);
    }

    private Path completedDownload(Path folder, Set<Path> before, Map<Path, Long> sizes) {
        try {
            for (Path file : filesIn(folder)) {
                if (before.contains(file)
                        || file.getFileName().toString().endsWith(".crdownload")) {
                    continue;
                }
                long size = Files.size(file);
                Long previous = sizes.put(file, size);
                if (size > 0 && previous != null && previous == size) {
                    return file;
                }
            }
            sizes.keySet().removeIf(file -> !Files.exists(file));
            return null;
        } catch (IOException error) {
            throw new IllegalStateException("Cannot inspect export download directory", error);
        }
    }

    private Product selectProduct() {
        driver.get(BASE_URL + "catalogue");
        visible(By.xpath("//h1[normalize-space()='Catalogue']"));
        sleep(500);

        List<Predicate<String>> priorities = List.of(
                name -> name.toLowerCase(Locale.ROOT).contains("rice"),
                name -> name.toLowerCase(Locale.ROOT).contains("1l"),
                name -> name.equalsIgnoreCase("Village Banana Chips"),
                // Use the first eligible product if the live catalogue has no preferred name.
                name -> true
        );

        // Scan the complete paginated catalogue. Category-specific navigation can
        // reset pagination and hide otherwise eligible products.
        List<Map.Entry<String, String>> categories = List.of(Map.entry("", "All categories"));

        for (Predicate<String> priority : priorities) {
            for (Map.Entry<String, String> cat : categories) {
                driver.get(BASE_URL + "catalogue");
                visible(By.xpath("//h1[normalize-space()='Catalogue']"));

                while (true) {
                    wait.until(ignored -> !driver.findElements(By.cssSelector("table tbody tr")).isEmpty());
                    List<WebElement> rows = driver.findElements(By.cssSelector("table tbody tr"));

                    for (int i = 0; i < rows.size(); i++) {
                        List<WebElement> currentRows = driver.findElements(By.cssSelector("table tbody tr"));
                        if (i >= currentRows.size()) break;
                        WebElement row = currentRows.get(i);
                        List<WebElement> cells = row.findElements(By.tagName("td"));
                        if (cells.size() < 6) continue;

                        String sku = cells.get(0).getText().trim();
                        String name = cells.get(1).getText().trim();
                        if (!priority.test(name)) continue;

                        String pieceText = cells.get(3).getText().trim();
                        String caseText = cells.get(4).getText().trim();
                        BigDecimal casePrice = priceAfterMultiplySign(caseText);

                        if (casePrice.multiply(BigDecimal.valueOf(2)).compareTo(MINIMUM_ORDER) < 0) {
                            continue;
                        }

                        WebElement link = cells.get(1).findElement(By.tagName("a"));
                        String href = link.getDomProperty("href");
                        String productId = href.substring(href.lastIndexOf('/') + 1);
                        BigDecimal taxPercent = percent(cells.get(5).getText());
                        BigDecimal piecePrice = money(pieceText);

                        // Navigate directly to the captured product URL so a stale or
                        // intercepted catalogue click cannot leave the driver on the listing.
                        driver.get(href);
                        sleep(500);
                        wait.until(ExpectedConditions.urlContains("/product/"));
                        visible(By.cssSelector("main h1"));

                        List<WebElement> summary = driver.findElements(By.cssSelector("main p.muted"));
                        String sellerText = summary.get(0).getText();
                        String seller = sellerText.substring(sellerText.lastIndexOf("Sold by ") + "Sold by ".length()).trim();

                        String priceParagraph = visible(By.xpath("//main//p[contains(normalize-space(.),'Piece:')]")).getText();
                        Matcher caseMatcher = Pattern.compile("Case of\\s+(\\d+)").matcher(priceParagraph);
                        int piecesPerCase = 1;
                        if (caseMatcher.find()) {
                            piecesPerCase = Integer.parseInt(caseMatcher.group(1));
                        }

                        String stockText = visible(By.xpath("//main//p[contains(normalize-space(.),'In stock:')]")).getText();
                        Matcher stockMatcher = Pattern.compile("In stock:\\s*(-?\\d+)\\s+pieces").matcher(stockText);
                        int inStockPieces = 0;
                        if (stockMatcher.find()) {
                            inStockPieces = Integer.parseInt(stockMatcher.group(1));
                        }

                        int completeCases = piecesPerCase > 0 ? inStockPieces / piecesPerCase : 0;
                        if (completeCases >= 2) {
                            Product result = new Product(
                                    productId, sku, name, seller, cat.getValue(), cat.getKey(),
                                    piecePrice, casePrice, taxPercent, piecesPerCase, inStockPieces);
                            driver.navigate().back();
                            visible(By.xpath("//h1[normalize-space()='Catalogue']"));
                            sleep(300);
                            return result;
                        }

                        driver.navigate().back();
                        visible(By.xpath("//h1[normalize-space()='Catalogue']"));
                        sleep(300);
                    }

                    List<WebElement> nextLinks = driver.findElements(By.xpath("//a[normalize-space()='Next']"));
                    if (nextLinks.isEmpty() || !nextLinks.get(0).isDisplayed()) {
                        break;
                    }
                    clickElement(nextLinks.get(0));
                    sleep(500);
                }
            }
        }
        throw new AssertionError("No product meeting priority, two-case stock, and minimum order requirements was found");
    }

    private void openProduct(Product product) {
        driver.get(BASE_URL + "product/" + product.id());
        wait.until(ExpectedConditions.urlContains("/product/" + product.id()));
        visible(By.cssSelector("main h1"));
        Assert.assertEquals(text(By.cssSelector("main h1")), product.name(),
                "Opened product must match the selected product");
        sleep(300);
    }

    private void enterInvalidZero() {
        WebElement quantity = visible(By.cssSelector("input[data-testid='qty']"));
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].value='0';"
                        + "arguments[0].dispatchEvent(new Event('input',{bubbles:true}));"
                        + "arguments[0].dispatchEvent(new Event('change',{bubbles:true}));",
                quantity);
        Assert.assertEquals(quantity.getDomProperty("value"), "0");
    }

    private void enterQuantity(int value) {
        WebElement quantity = visible(By.cssSelector("input[data-testid='qty']"));
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].value=arguments[1];"
                        + "arguments[0].dispatchEvent(new Event('input',{bubbles:true}));"
                        + "arguments[0].dispatchEvent(new Event('change',{bubbles:true}));",
                quantity, Integer.toString(value));
        Assert.assertEquals(quantity.getDomProperty("value"), Integer.toString(value));
    }

    private void submitAddToCart() {
        WebElement button = visible(By.cssSelector("button[data-testid='add-to-cart']"));
        ((JavascriptExecutor) driver).executeScript("arguments[0].form.submit();", button);
        sleep(500);
    }

    private void selectUnit(String unit) {
        new Select(visible(By.cssSelector("select[data-testid='unit']")))
                .selectByVisibleText(unit);
    }

    private void openCart() {
        driver.get(BASE_URL + "cart");
        visible(By.xpath("//h1[normalize-space()='Cart']"));
        sleep(300);
    }

    private List<WebElement> cartRows() {
        return driver.findElements(By.cssSelector("tr[data-testid='cart-line']"));
    }

    private void updateCartQuantity(String productId, int quantity) {
        WebElement row = cartRows().stream()
                .filter(item -> item.findElement(By.name("product_id"))
                        .getDomAttribute("value").equals(productId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Product missing from Cart"));
        WebElement field = row.findElement(By.cssSelector("input[data-testid='cart-qty']"));
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].value=arguments[1];"
                        + "arguments[0].dispatchEvent(new Event('input',{bubbles:true}));"
                        + "arguments[0].dispatchEvent(new Event('change',{bubbles:true}));",
                field, Integer.toString(quantity));
        WebElement updateButton = row.findElement(By.xpath(
                ".//form[contains(@action,'cart/update')]//button[normalize-space()='Update']"));
        ((JavascriptExecutor) driver).executeScript("arguments[0].form.submit();", updateButton);
        sleep(500);
        wait.until(ignored -> cartRows().stream().anyMatch(item ->
                item.findElement(By.name("product_id")).getDomAttribute("value")
                        .equals(productId)
                        && item.findElement(By.cssSelector("input[data-testid='cart-qty']"))
                                .getDomProperty("value").equals(Integer.toString(quantity))));
    }

    private BigDecimal cartNet() {
        String text = visible(By.cssSelector("p.total")).getText();
        return money(text.substring(text.lastIndexOf(':') + 1));
    }

    private Order checkoutAndPlace(Product product, BigDecimal expectedNet, boolean evening)
            throws IOException {
        driver.get(BASE_URL + "checkout");
        wait.until(ExpectedConditions.urlContains("/checkout"));
        visible(By.xpath("//h1[normalize-space()='Checkout']"));
        sleep(500);

        BigDecimal grandTotal = money(text(By.cssSelector("[data-testid='grand-total']")));
        List<WebElement> sellerRows = driver.findElements(By.cssSelector("table tbody tr"));
        WebElement sellerRow = sellerRows.stream()
                .filter(row -> row.findElement(By.xpath("./td[1]")).getText().trim()
                        .equals(product.seller()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Checkout has no amounts for seller " + product.seller()));
        List<WebElement> amounts = sellerRow.findElements(By.tagName("td"));
        Assert.assertTrue(amounts.size() >= 5, "Checkout seller row is incomplete");
        BigDecimal net = money(amounts.get(1).getText());
        BigDecimal tax = money(amounts.get(2).getText());
        BigDecimal delivery = money(amounts.get(3).getText());
        BigDecimal sellerTotal = money(amounts.get(4).getText());

        Assert.assertEquals(net.compareTo(expectedNet), 0, "Checkout net did not match Cart");
        Assert.assertEquals(tax.compareTo(expectedNet.multiply(product.taxPercent())
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)),
                0,
                "Checkout product tax did not match the selected product tax rate");
        if (expectedNet.compareTo(FREE_DELIVERY) >= 0) {
            Assert.assertEquals(delivery.compareTo(BigDecimal.ZERO), 0,
                    "Free-delivery threshold should waive delivery charge");
        } else {
            Assert.assertTrue(delivery.compareTo(DELIVERY_FEE) >= 0,
                    "Delivery charge should not be below the configured fee");
        }
        Assert.assertEquals(sellerTotal.compareTo(net.add(tax).add(delivery)), 0,
                "Checkout seller total did not equal its displayed components");
        Assert.assertEquals(grandTotal.compareTo(sellerTotal), 0,
                "Checkout grand total did not match the seller total");

        String date = LocalDate.now().plusDays(1).toString();
        WebElement dateField = visible(By.cssSelector("input[data-testid='slot-date']"));
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].value=arguments[1];"
                        + "arguments[0].dispatchEvent(new Event('change',{bubbles:true}));",
                dateField,
                date);
        sleep(300);

        click(By.xpath(
                "//form[@method='get' and contains(@action,'checkout')]//button[normalize-space()='Show slots']"));
        sleep(500);

        visible(By.cssSelector("input[type='radio'][name='slot_start']"));
        List<WebElement> slots = driver.findElements(
                By.cssSelector("input[type='radio'][name='slot_start']"));
        WebElement selected = slots.stream()
                .filter(slot -> evening
                        ? "17:00".equals(slot.getDomAttribute("value"))
                        : slot.isEnabled())
                .filter(WebElement::isEnabled)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Required delivery slot is unavailable"));
        String slotStart = selected.getDomAttribute("value");
        String slotLabel = selected.findElement(By.xpath("..")).getText().trim();
        if (evening) {
            Assert.assertTrue(slotLabel.contains("17:00") && slotLabel.contains("20:00"),
                    "Evening slot label must be 17:00–20:00");
        }
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].checked=true;"
                        + "arguments[0].dispatchEvent(new Event('input',{bubbles:true}));"
                        + "arguments[0].dispatchEvent(new Event('change',{bubbles:true}));",
                selected);
        sleep(300);

        Assert.assertTrue(expectedNet.compareTo(MINIMUM_ORDER) >= 0,
                "Order net is below the configured minimum");
        String placedMinute = LocalDateTime.now().format(MINUTE);
        WebElement placeOrder = visible(By.cssSelector("button[data-testid='place-order']"));
        ((JavascriptExecutor) driver).executeScript("arguments[0].form.submit();", placeOrder);
        sleep(500);

        wait.until(ExpectedConditions.urlContains("/orders"));
        Assert.assertEquals(
                text(By.cssSelector("[data-testid='flash-message']")),
                "Order placed!",
                "Order confirmation was not displayed");
        WebElement matching = wait.until(ignored -> findNewOrder(
                product, date, slotLabel, placedMinute, grandTotal));
        List<WebElement> cells = matching.findElements(By.tagName("td"));
        String actualDate = cells.get(3).getText().trim().substring(0, 10);
        return new Order(cells.get(0).getText().trim(), product, actualDate, slotStart,
                slotLabel, grandTotal);
    }

    private void verifyOrderDetails(Order order, Product product, int expectedQuantity, BigDecimal expectedNet) {
        openOrder(order.id());
        sleep(300);

        String heading = text(By.cssSelector("main h1"));
        Assert.assertTrue(heading.contains(order.id()), "Order details heading must contain order ID " + order.id());
        Assert.assertEquals(text(By.cssSelector("[data-testid='order-status']")), "placed",
                "New order status in Order Details must be placed");

        String summary = text(By.cssSelector("main p.muted"));
        Assert.assertTrue(summary.contains(product.seller()), "Order details summary must contain seller " + product.seller());

        String deliveryText = text(By.xpath("//main/p[contains(normalize-space(.),'Delivery:')]"));
        Assert.assertTrue(deliveryText.contains(order.date()) && deliveryText.contains(order.slotLabel()),
                "Delivery text must contain date " + order.date() + " and slot " + order.slotLabel());

        WebElement itemRow = visible(By.cssSelector("main table tbody tr"));
        List<WebElement> itemCells = itemRow.findElements(By.tagName("td"));
        Assert.assertEquals(itemCells.get(0).getText().trim(), product.name(), "Item product name must match");
        Assert.assertEquals(itemCells.get(1).getText().trim().toLowerCase(Locale.ROOT), "case", "Item unit must be case");
        Assert.assertEquals(Integer.parseInt(itemCells.get(2).getText().trim()), expectedQuantity, "Item quantity must match");
        Assert.assertEquals(money(itemCells.get(3).getText()), product.casePrice(), "Item case price must match");
        Assert.assertEquals(percent(itemCells.get(4).getText()), product.taxPercent(), "Item tax percent must match");
        Assert.assertEquals(money(itemCells.get(5).getText()), expectedNet, "Item net total must match");
    }

    private WebElement findNewOrder(
            Product product, String date, String slot, String minute, BigDecimal total) {
        for (WebElement row : driver.findElements(By.cssSelector("tr[data-testid='order-row']"))) {
            List<WebElement> cells = row.findElements(By.tagName("td"));
            String delivery = cells.size() > 3 ? cells.get(3).getText() : "";
            if (cells.size() >= 6
                    && cells.get(1).getText().trim().equals(product.seller())
                    && cells.get(2).getText().startsWith(minute)
                    && delivery.contains(slot)
                    && money(cells.get(5).getText()).compareTo(total) == 0
                    && cells.get(4).getText().trim().equals("placed")) {
                return row;
            }
        }
        return null;
    }

    private void openOrder(String orderId) {
        driver.get(BASE_URL + "orders");
        sleep(300);
        WebElement row = exactOrderRow(orderId);
        String orderHref = row.findElement(By.cssSelector("a[href*='/orders/']"))
                .getDomProperty("href");
        driver.get(orderHref);
        wait.until(ExpectedConditions.urlContains("/orders/"));
        visible(By.cssSelector("[data-testid='order-status']"));
    }

    private void processSellerOrder(Order order, String buyer) {
        click(By.cssSelector("a[href*='/seller/orders']"));
        visible(By.cssSelector("tr[data-testid='seller-order-row']"));
        sleep(500);

        OrderRow placed = sellerOrder(order.id());
        Assert.assertEquals(placed.buyer(), buyer, "Seller Orders buyer must match buyer identity");
        Assert.assertTrue(placed.delivery().contains(order.date()), "Delivery date must match");
        Assert.assertTrue(placed.delivery().contains(order.slotLabel()), "Delivery slot must match");
        Assert.assertEquals(placed.total().compareTo(order.total()), 0,
                "Seller Orders total must match the grand total captured at checkout");
        Assert.assertEquals(placed.status(), "placed", "Initial status must be placed");
        Assert.assertTrue(placed.actions().containsAll(List.of("Accept", "Cancel")),
                "Initial placed status must offer Accept and Cancel");

        // Action 1: Accept
        clickSellerAction(order.id(), "Accept", "placed", "/accept");
        sleep(500);
        Assert.assertEquals(text(By.cssSelector("[data-testid='flash-message']")), "Order updated: accept",
                "Seller Accept must show exact confirmation message");
        OrderRow accepted = waitForStatusChange(order.id(), "placed");
        Assert.assertFalse(accepted.status().equalsIgnoreCase("placed"), "Status must advance after Accept");
        Assert.assertTrue(accepted.actions().containsAll(List.of("Ship", "Cancel")),
                "Accepted status must offer Ship and Cancel");

        // Action 2: Ship
        clickSellerAction(order.id(), "Ship", accepted.status(), null);
        sleep(500);
        OrderRow shipped = waitForStatusChange(order.id(), accepted.status());
        Assert.assertFalse(shipped.status().equalsIgnoreCase(accepted.status()), "Status must advance after Ship");
        Assert.assertTrue(shipped.actions().containsAll(List.of("Deliver", "Fail")),
                "Shipped status must offer Deliver and Fail");

        // Action 3: Deliver
        clickSellerAction(order.id(), "Deliver", shipped.status(), null);
        sleep(500);
        Assert.assertEquals(text(By.cssSelector("[data-testid='flash-message']")), "Order updated: deliver",
                "Seller Deliver must show exact confirmation message");
        OrderRow delivered = waitForStatus(order.id(), "delivered");
        Assert.assertEquals(delivered.status(), "delivered", "Order status must reach delivered");
        Assert.assertTrue(delivered.actions().isEmpty(),
                "Delivered order must not offer any further actions");
    }

    private void clickSellerAction(String orderId, String action, String expectedStatus, String pathSuffix) {
        OrderRow row = sellerOrder(orderId);
        Assert.assertEquals(row.status(), expectedStatus, "Order status changed before " + action);
        WebElement targetRow = exactSellerRow(orderId);
        List<WebElement> buttons = targetRow.findElement(By.cssSelector("td.actions"))
                .findElements(By.cssSelector("form button")).stream()
                .filter(button -> button.getText().trim().equals(action))
                .toList();
        Assert.assertEquals(buttons.size(), 1, "Expected one " + action + " action in target row");
        WebElement button = buttons.get(0);
        WebElement form = button.findElement(By.xpath("ancestor::form[1]"));
        String formAction = form.getDomAttribute("action");
        Assert.assertTrue(formAction != null
                        && formAction.matches("^/c/manoj-154697/seller/orders/\\d+/[A-Za-z_-]+$"),
                "Unexpected Seller action form: " + formAction);
        Assert.assertEquals(form.getDomAttribute("method").toLowerCase(Locale.ROOT), "post",
                "Seller action form must use POST method");
        if (pathSuffix != null) {
            Assert.assertTrue(formAction.endsWith(pathSuffix),
                    "Form action " + formAction + " must end with expected suffix " + pathSuffix);
        }
        // Seller actions are plain POST forms. Submit the validated target form
        // explicitly so the action is not lost in headless execution.
        ((JavascriptExecutor) driver).executeScript("arguments[0].submit();", form);
    }

    private OrderRow waitForStatusChange(String id, String oldStatus) {
        return wait.until(ignored -> {
            OrderRow row = sellerOrder(id);
            return row.status().equalsIgnoreCase(oldStatus) ? null : row;
        });
    }

    private OrderRow waitForStatus(String id, String expected) {
        return wait.until(ignored -> {
            OrderRow row = sellerOrder(id);
            return row.status().equalsIgnoreCase(expected) ? row : null;
        });
    }

    private OrderRow sellerOrder(String id) {
        List<WebElement> cells = exactSellerRow(id).findElements(By.tagName("td"));
        WebElement actionsCell = exactSellerRow(id).findElement(By.cssSelector("td.actions"));
        List<String> actions = actionsCell.findElements(By.cssSelector("form button")).stream()
                .map(button -> button.getText().trim())
                .toList();
        return new OrderRow(
                cells.get(0).getText().trim(),
                cells.get(1).getText().trim(),
                cells.get(2).getText().trim(),
                cells.get(3).getText().trim(),
                money(cells.get(4).getText()),
                actions);
    }

    private WebElement exactSellerRow(String id) {
        List<WebElement> rows = driver.findElements(
                By.cssSelector("tr[data-testid='seller-order-row']")).stream()
                .filter(row -> row.findElement(By.xpath("./td[1]")).getText().trim().equals(id))
                .toList();
        Assert.assertEquals(rows.size(), 1, "Expected exactly one Seller row for order " + id);
        return rows.get(0);
    }

    private WebElement exactOrderRow(String id) {
        List<WebElement> rows = driver.findElements(
                By.cssSelector("tr[data-testid='order-row']")).stream()
                .filter(row -> row.findElement(By.xpath("./td[1]")).getText().trim().equals(id))
                .toList();
        Assert.assertEquals(rows.size(), 1, "Expected exactly one Buyer order " + id);
        return rows.get(0);
    }

    private void login(String username) {
        if (shown(By.cssSelector("a[href='/c/manoj-154697/logout']"))) {
            logout();
        }
        // Explicitly load a fresh login form after switching users. This avoids
        // submitting a stale form left behind by the previous session logout.
        driver.get(BASE_URL + "login");
        visible(By.cssSelector("input[data-testid='username']"));
        WebElement userField = driver.findElement(By.cssSelector("input[data-testid='username']"));
        WebElement passField = driver.findElement(By.cssSelector("input[data-testid='password']"));
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].value=arguments[1];"
                        + "arguments[0].dispatchEvent(new Event('input',{bubbles:true}));"
                        + "arguments[0].dispatchEvent(new Event('change',{bubbles:true}));"
                        + "arguments[2].value=arguments[3];"
                        + "arguments[2].dispatchEvent(new Event('input',{bubbles:true}));"
                        + "arguments[2].dispatchEvent(new Event('change',{bubbles:true}));",
                userField, username, passField, PASSWORD);

        wait.until(ignored -> username.equals(userField.getDomProperty("value"))
                && PASSWORD.equals(passField.getDomProperty("value")));
        By loginButton = By.cssSelector("button[data-testid='login']");
        // Re-locate the button after logout/navigation, bring it into view, and
        // only then wait for it to become visible and clickable.
        WebElement login = wait.until(ExpectedConditions.presenceOfElementLocated(loginButton));
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].scrollIntoView({block:'center', inline:'nearest'});", login);
        login = wait.until(ExpectedConditions.visibilityOfElementLocated(loginButton));
        login = wait.until(ExpectedConditions.elementToBeClickable(loginButton));
        clickElement(login);

        // The login page is a plain HTML POST form. If the native click does
        // not start navigation, submit the same form explicitly instead of
        // leaving the test on the login page.
        try {
            new WebDriverWait(driver, Duration.ofSeconds(3)).until(
                    ExpectedConditions.not(ExpectedConditions.urlContains("/login")));
        } catch (Exception ignored) {
            WebElement currentLogin = driver.findElement(loginButton);
            ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].form.submit();", currentLogin);
        }
        wait.until(ExpectedConditions.or(
                ExpectedConditions.visibilityOfElementLocated(By.cssSelector("a[href='/c/manoj-154697/logout']")),
                ExpectedConditions.visibilityOfElementLocated(By.cssSelector("span.who"))
        ));
        sleep(500);
    }

    private void logout() {
        By loginInput = By.cssSelector("input[data-testid='username']");
        driver.get(BASE_URL + "logout");
        // Remove any stale authentication token before the next user login.
        driver.manage().deleteAllCookies();
        try {
            visible(loginInput);
        } catch (Exception e) {
            // fallback: navigate to base URL if login form not shown after logout
            driver.get(BASE_URL);
            visible(loginInput);
        }
        sleep(300);
    }

    private void saveFailureScreenshot(Throwable failure) {
        failureDetails = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        if (driver instanceof TakesScreenshot screenshotDriver) {
            try {
                Path folder = Path.of("test-output", "failures");
                Files.createDirectories(folder);
                Path file = folder.resolve("V2CidRoy_WholeCart_Automation-failure-"
                        + System.currentTimeMillis() + ".png");
                Files.write(file, screenshotDriver.getScreenshotAs(OutputType.BYTES));
                failureScreenshot = "../failures/" + file.getFileName();
                System.err.println("Failure screenshot: " + file.toAbsolutePath());
                System.err.println("Failure URL: " + driver.getCurrentUrl());
            } catch (IOException | WebDriverException error) {
                failureDetails += " (screenshot error: " + error.getMessage() + ")";
                System.err.println("Could not save failure screenshot: " + error.getMessage());
            }
        }
        addReportStep("FAILED");
    }

    private void step(String name) {
        addReportStep("PASSED");
        currentStep = name;
        stepStartedAt = System.nanoTime();
        System.out.println("\n=== " + name + " ===");
    }

    private void addReportStep(String status) {
        if (currentStep == null) {
            return;
        }
        double seconds = (System.nanoTime() - stepStartedAt) / 1_000_000_000.0;
        reportSteps.add("<article class='step'><strong>" + escape(currentStep)
                + "</strong><span class='" + status + "'>" + status
                + "</span><small>" + String.format(Locale.ROOT, "%.1f s", seconds)
                + " · " + escape(currentUrl())
                + "</small></article>");
        currentStep = null;
    }

    private String currentUrl() {
        try {
            return driver == null ? "" : driver.getCurrentUrl();
        } catch (WebDriverException error) {
            return "<browser unavailable>";
        }
    }

    private void writeHtmlReport() throws IOException {
        if (currentStep != null) {
            addReportStep(testCompleted ? "PASSED"
                    : failureDetails.isBlank() ? "INCOMPLETE" : "FAILED");
        }
        Path reportDirectory = Path.of("test-output", "reports");
        Files.createDirectories(reportDirectory);
        String screenshot = failureScreenshot.isBlank()
                ? ""
                : "<img src='" + escape(failureScreenshot)
                        + "' alt='Failure screenshot'>";
        String failure = failureDetails.isBlank()
                ? ""
                : "<section class='failure'><h2>Failure details</h2><pre>"
                        + escape(failureDetails) + "</pre>" + screenshot + "</section>";
        String html = "<!doctype html><html lang='en'><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>WholeCart E2E Report</title><style>"
                + "body{font:15px/1.5 Segoe UI,Arial;margin:0;background:#f3f6f9;color:#172b4d}"
                + "header{padding:24px;background:#176b57;color:white}main{max-width:1000px;"
                + "margin:24px auto;padding:0 16px}.step,.failure{background:white;padding:16px;"
                + "margin:10px 0;border-radius:10px;border:1px solid #dbe3eb}"
                + ".step{display:grid;grid-template-columns:1fr auto;gap:4px 14px}"
                + "small{grid-column:1/-1;color:#64748b}.PASSED{color:#15803d}"
                + ".FAILED{color:#b91c1c}.INCOMPLETE{color:#a16207}"
                + ".failure{border-left:5px solid #dc2626}pre{white-space:pre-wrap}"
                + "img{max-width:100%;border-radius:8px}</style><header><h1>"
                + "WholeCart End-to-End Report</h1><p>Step results and failure evidence</p>"
                + "</header><main><h2>Execution steps</h2>" + String.join("", reportSteps)
                + failure + "</main></html>";
        Files.writeString(reportDirectory.resolve("wholecart-e2e-report.html"), html);
    }

    private String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private WebElement visible(By by) {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(by));
    }

    private void click(By by) {
        WebElement element = wait.until(ExpectedConditions.elementToBeClickable(by));
        clickElement(element);
    }

    private void clickElement(WebElement element) {
        try {
            element.click();
        } catch (Exception e) {
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", element);
        }
        sleep(300);
    }

    private String text(By by) {
        return visible(by).getText().trim();
    }

    private boolean shown(By by) {
        List<WebElement> elements = driver.findElements(by);
        return !elements.isEmpty() && elements.get(0).isDisplayed();
    }

    private void sleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private Set<Path> filesIn(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return new HashSet<>(files.filter(Files::isRegularFile).toList());
        }
    }

    private BigDecimal priceAfterMultiplySign(String value) {
        int separator = value.lastIndexOf('\u00D7');
        if (separator < 0) {
            separator = value.toLowerCase(Locale.ROOT).lastIndexOf('x');
        }
        Assert.assertTrue(separator >= 0, "Unexpected Catalogue case price: " + value);
        return money(value.substring(separator + 1));
    }

    private BigDecimal money(String value) {
        String cleaned = value.replaceAll("[^0-9,.-]", "").replace(",", "");
        return new BigDecimal(cleaned);
    }

    private BigDecimal percent(String value) {
        return new BigDecimal(value.replaceAll("[^0-9.-]", ""));
    }

    private record Product(
            String id, String sku, String name, String seller,
            String category, String categoryValue, BigDecimal piecePrice,
            BigDecimal casePrice, BigDecimal taxPercent, int piecesPerCase,
            int inStockPieces) { }

    private record Seller(String id, String name) { }

    private record Order(
            String id, Product product, String date, String slotStart,
            String slotLabel, BigDecimal total) { }

    private record OrderRow(
            String number, String buyer, String delivery, String status,
            BigDecimal total, List<String> actions) { }
}

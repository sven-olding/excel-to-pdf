package com.gi.excelToPdf;

import com.itextpdf.io.image.ImageData;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.Color;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.*;
import com.itextpdf.layout.element.AreaBreak;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.property.AreaBreakType;
import com.itextpdf.layout.property.TextAlignment;
import com.itextpdf.layout.property.VerticalAlignment;
import com.itextpdf.layout.renderer.CellRenderer;
import com.itextpdf.layout.renderer.DrawContext;
import com.itextpdf.layout.renderer.IRenderer;
import com.itextpdf.layout.renderer.TableRenderer;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.*;

import java.awt.*;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class App {
    private static final HashMap<String, String> FONT_MAP = new HashMap<>();
    private static final DataFormatter DATA_FORMATTER = new DataFormatter();

    public static void main(String[] args) throws Exception {
        App app = new App();

        app.convertToPdf("./target/classes/input1.xlsx", "./target/output1.pdf");
        app.convertToPdf("./target/classes/input2.xlsx", "./target/output2.pdf");
    }


    public void convertToPdf(String xlsPath, String pdfPath) throws IOException {
        initFonts();

        try (InputStream inputStream = new FileInputStream(xlsPath)) {
            try (Workbook workbook = new XSSFWorkbook(inputStream)) {

                PdfDocument pdfDoc = new PdfDocument(new PdfWriter(pdfPath));
                Document doc = setupDocument(workbook, pdfDoc);
                pdfDoc.addNewPage();

                for (int sheetNum = 0; sheetNum < workbook.getNumberOfSheets(); sheetNum++) {
                    Sheet sheet = workbook.getSheetAt(sheetNum);
                    if (sheetNum >= 1) {
                        doc.add(new AreaBreak(AreaBreakType.NEXT_PAGE));
                    }

                    // get number of rows and cols to print
                    int firstColumn = 0;
                    int firstRow = sheet.getFirstRowNum();
                    int lastRow = sheet.getLastRowNum();
                    int numCols = getNumberOfColumns(sheet);

                    String printArea = workbook.getPrintArea(sheetNum);
                    if (printArea != null && printArea.contains("!")) {
                        String[] printAreaData = printArea.split("!");
                        String[] range = printAreaData[1].split(":");
                        String[] firstRef = range[0].split("\\$");
                        String[] secondRef = range[1].split("\\$");
                        firstColumn = CellReference.convertColStringToIndex(firstRef[1]);
                        int lastColumnNum = CellReference.convertColStringToIndex(secondRef[1]);
                        numCols = (lastColumnNum - firstColumn) + 1;
                        if (firstRef.length == 3) {
                            firstRow = Integer.parseInt(firstRef[2]) - 1;
                        }
                        if (secondRef.length == 3) {
                            lastRow = Integer.parseInt(secondRef[2]) - 1;
                        }
                    }

                    // calculate columnWidth
                    float[] columnWidth = new float[numCols];
                    for (int j = 0; j < numCols; j++) {
                        float columnWidthInPixels = sheet.getColumnWidthInPixels(j);
                        double columnWidthInPoints = columnWidthInPixels * 0.75d;
                        columnWidth[j] = (float) columnWidthInPoints;
                    }

                    Table table = new Table(columnWidth);
                    table.useAllAvailableWidth();

                    for (int rowNum = firstRow; rowNum <= lastRow; rowNum++) {
                        Row row = sheet.getRow(rowNum);
                        if (row == null) {
                            continue;
                        }

                        for (int columnNum = firstColumn; columnNum < numCols; columnNum++) {
                            Cell cell = row.getCell(columnNum, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);

                            // check if we need colspan for merged cells
                            int mergedCellIndex = getIndexForMergedCell(sheet, cell);
                            int colSpan = 1;
                            if (mergedCellIndex > -1) {
                                CellRangeAddress mergedRegion = sheet.getMergedRegion(mergedCellIndex);
                                int first = mergedRegion.getFirstColumn();
                                int last = mergedRegion.getLastColumn();
                                columnNum = last;
                                colSpan = (last - first) + 1;
                            }

                            // create new cell
                            com.itextpdf.layout.element.Cell pdfCell = createPdfCell(cell,
                                    colSpan, row.getHeightInPoints());
                            table.addCell(pdfCell);
                        }
                    }

                    // add pictures
                    Map<Point, XSSFPicture> pictureMap = new HashMap<>();
                    XSSFDrawing dp = (XSSFDrawing) sheet.createDrawingPatriarch();
                    List<XSSFShape> pics = dp.getShapes();
                    for (XSSFShape p : pics) {
                        XSSFPicture pic = (XSSFPicture) p;
                        XSSFClientAnchor clientAnchor = pic.getClientAnchor();
                        pictureMap.put(new Point(clientAnchor.getCol1(), clientAnchor.getRow1()), pic);
                    }
                    table.setNextRenderer(new ImageTableRenderer(table, pictureMap));

                    doc.add(table);
                }

                doc.close();
                pdfDoc.close();
            }
        }
    }

    private int getNumberOfColumns(Sheet sheet) {
        int firstRowNum = sheet.getFirstRowNum();
        int lastRowNum = sheet.getLastRowNum();
        for (int rowNum = firstRowNum; rowNum < lastRowNum; rowNum++) {
            Row row = sheet.getRow(rowNum);
            if (row == null) {
                continue;
            }
            if (row.getLastCellNum() > -1) {
                return row.getLastCellNum();
            }
        }
        return -1;
    }

    private void initFonts() {
        FONT_MAP.put("Arial", "C:\\Windows\\Fonts\\arial.ttf");
        FONT_MAP.put("Frutiger LT Com 45 Light",
                System.getProperty("user.home") +
                        "\\AppData\\Local\\Microsoft\\Windows\\Fonts\\Frutiger-LT-Com-45-Light.ttf");
    }

    private int getIndexForMergedCell(Sheet sheet, Cell cell) {
        int numMerged = sheet.getNumMergedRegions();
        for (int i = 0; i < numMerged; i++) {
            CellRangeAddress mergedCell = sheet.getMergedRegion(i);
            if (mergedCell.isInRange(cell)) {
                return i;
            }
        }
        return -1;
    }

    private com.itextpdf.layout.element.Cell createPdfCell(Cell xlsCell, int colSpan, float rowHeightInPoints) {
        com.itextpdf.layout.element.Cell pdfCell = new com.itextpdf.layout.element.Cell(1, colSpan);

        String cellValueAsString = getCellValueAsString(xlsCell);
        Paragraph paragraph = new Paragraph().add(cellValueAsString);
        XSSFCellStyle cellStyle = (XSSFCellStyle) xlsCell.getCellStyle();
        String fontName = cellStyle.getFont().getFontName();
        if (FONT_MAP.containsKey(fontName)) {
            try {
                PdfFont font = PdfFontFactory.createFont(FONT_MAP.get(fontName),
                        PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED);
                pdfCell.setFont(font);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        float rowHeight = rowHeightInPoints * 0.77f;
        float fontSize = cellStyle.getFont().getFontHeightInPoints();
        if (fontSize + 3f > rowHeight) {
            fontSize = (float) Math.floor(rowHeight) - 3f;
        }
        paragraph.setFontSize(fontSize);
        if (cellStyle.getFont().getBold()) {
            paragraph.setBold();
        }
        if (cellStyle.getFont().getItalic()) {
            paragraph.setItalic();
        }
        paragraph.setFontColor(getRgbColorObject(cellStyle.getFont().getXSSFColor()));

        setAlignment(pdfCell, paragraph, cellStyle);
        setCellBorder(xlsCell, pdfCell);
        return pdfCell
                .setBackgroundColor(getRgbColorObject(cellStyle.getFillForegroundXSSFColor()))
                .add(paragraph)
                .setHeight(rowHeight)
                .setMargins(0f, 0f, 0f, 0f)
                .setPaddings(1.5f, 1.5f, 1.5f, 1.5f);
    }

    private void setAlignment(com.itextpdf.layout.element.Cell pdfCell, Paragraph paragraph, XSSFCellStyle cellStyle) {
        switch (cellStyle.getAlignment()) {
            case LEFT:
                pdfCell.setHorizontalAlignment(com.itextpdf.layout.property.HorizontalAlignment.LEFT);
                paragraph.setTextAlignment(TextAlignment.LEFT);
                break;
            case CENTER:
                pdfCell.setHorizontalAlignment(com.itextpdf.layout.property.HorizontalAlignment.CENTER);
                paragraph.setTextAlignment(TextAlignment.CENTER);
                break;
            case RIGHT:
                pdfCell.setHorizontalAlignment(com.itextpdf.layout.property.HorizontalAlignment.RIGHT);
                paragraph.setTextAlignment(TextAlignment.RIGHT);
                break;
        }
        switch (cellStyle.getVerticalAlignment()) {
            case TOP:
                pdfCell.setVerticalAlignment(VerticalAlignment.TOP);
                break;
            case CENTER:
                pdfCell.setVerticalAlignment(VerticalAlignment.MIDDLE);
                break;
            case BOTTOM:
                pdfCell.setVerticalAlignment(VerticalAlignment.BOTTOM);
                break;
        }
    }

    private void setCellBorder(Cell xlsCell, com.itextpdf.layout.element.Cell pdfCell) {
        XSSFCellStyle cellStyle = (XSSFCellStyle) xlsCell.getCellStyle();
        pdfCell.setBorderBottom(
                getPdfBorder(cellStyle.getBorderBottom(), cellStyle.getBottomBorderXSSFColor()));

        pdfCell.setBorderLeft(
                getPdfBorder(cellStyle.getBorderLeft(), cellStyle.getLeftBorderXSSFColor()));

        pdfCell.setBorderRight(
                getPdfBorder(cellStyle.getBorderRight(), cellStyle.getRightBorderXSSFColor()));

        pdfCell.setBorderTop(
                getPdfBorder(cellStyle.getBorderTop(), cellStyle.getTopBorderXSSFColor()));
    }

    private DeviceRgb getRgbColorObject(XSSFColor xssfColor) {
        if (xssfColor == null) {
            return null;
        }
        byte[] rgb = xssfColor.getRGB();
        if (rgb == null) {
            return null;
        }
        return new DeviceRgb(Byte.toUnsignedInt(rgb[0]), Byte.toUnsignedInt(rgb[1]), Byte.toUnsignedInt(rgb[2]));
    }

    private Border getPdfBorder(BorderStyle bs, XSSFColor xssfColor) {
        DeviceRgb defaultColor = new DeviceRgb(0, 0, 0);
        DeviceRgb rgbColorObject = getRgbColorObject(xssfColor);
        Color color = rgbColorObject != null ? rgbColorObject : defaultColor;

        switch (bs) {
            case DASHED:
            case MEDIUM_DASHED:
                return new DashedBorder(color, 1);
            case DOTTED:
                return new DottedBorder(color, 1);
            case THIN:
                return new SolidBorder(color, 0.5f);
            case THICK:
                return new SolidBorder(color, 1.5f);
            case MEDIUM:
                return new SolidBorder(color, 1);
            case DOUBLE:
                return new DoubleBorder(color, 100);
            default:
                return Border.NO_BORDER;
        }
    }

    private String getCellValueAsString(Cell cell) {
        String value;
        if (cell.getCellType() == CellType.FORMULA) {
            try {
                CellStyle cellStyle = cell.getCellStyle();
                value = DATA_FORMATTER.formatRawCellContents(cell.getNumericCellValue(),
                        cellStyle.getDataFormat(), cellStyle.getDataFormatString());
            } catch (IllegalStateException e) {
                try {
                    value = cell.getStringCellValue();
                } catch (IllegalStateException e1) {
                    value = "";
                }
            }
        } else {
            value = DATA_FORMATTER.formatCellValue(cell);
        }
        return value;
    }


    private Document setupDocument(Workbook workbook, PdfDocument pdfDoc) {
        Sheet sheet = workbook.getSheetAt(0);
        PrintSetup printSetup = sheet.getPrintSetup();
        PageSize pageSize;
        switch (printSetup.getPaperSize()) {
            case PrintSetup.A3_PAPERSIZE:
                pageSize = PageSize.A3;
                break;
            case PrintSetup.A5_PAPERSIZE:
                pageSize = PageSize.A5;
                break;
            case PrintSetup.B5_PAPERSIZE:
                pageSize = PageSize.B5;
                break;
            default:
                pageSize = PageSize.A4;
        }
        if (printSetup.getLandscape()) {
            pageSize = pageSize.rotate();
        }
        Document doc = new Document(pdfDoc, pageSize);
        float leftMargin = (float) sheet.getMargin(Sheet.LeftMargin);
        float rightMargin = (float) sheet.getMargin(Sheet.RightMargin);
        float topMargin = (float) sheet.getMargin(Sheet.TopMargin);
        float bottomMargin = (float) sheet.getMargin(Sheet.BottomMargin);
        // margins from sheet are in inch, multiply with 72 got get size in points for itext margins
        doc.setMargins(topMargin * 72, rightMargin * 72,
                bottomMargin * 72, leftMargin * 72);
        return doc;
    }

    private static class ImageTableRenderer extends TableRenderer {
        private final Map<Point, XSSFPicture> pictureMap;

        public ImageTableRenderer(Table modelElement, Map<Point, XSSFPicture> pictureMap) {
            super(modelElement);
            this.pictureMap = pictureMap;
        }

        @Override
        public void drawChildren(DrawContext drawContext) {
            super.drawChildren(drawContext);

            if (pictureMap == null || pictureMap.isEmpty()) {
                return;
            }

            pictureMap.forEach((point, picture) -> {
                if (point.y < rows.size()) {
                    CellRenderer[] cellRenderers = rows.get(point.y);

                    com.itextpdf.kernel.geom.Rectangle rect = cellRenderers[point.x].getOccupiedAreaBBox();
                    ImageData imageData = ImageDataFactory.create(picture.getPictureData().getData());

                    Dimension dimension = picture.getImageDimension();
                    float width = (float) (dimension.getWidth() * 0.75d);    // pixel to point
                    float height = (float) (dimension.getHeight() * 0.75d);  // pixel to point
                    com.itextpdf.kernel.geom.Rectangle imageRect = rect.clone();
                    imageRect.setWidth(width);
                    imageRect.setHeight(height);
                    drawContext.getCanvas().addImageFittedIntoRectangle(imageData, imageRect, false);
                }
            });

        }

        @Override
        public IRenderer getNextRenderer() {
            return new ImageTableRenderer((Table) modelElement, pictureMap);
        }
    }
}

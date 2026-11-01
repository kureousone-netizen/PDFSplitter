package com.example.pdfsplitter;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.converter.IntegerStringConverter;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Comparator;
import java.util.Optional;

public class PDFSplitter extends Application {

    /** Editable table row. Chapter is an immutable record, so the UI uses this instead. */
    public static class ChapterRow {
        private final StringProperty title = new SimpleStringProperty();
        private final IntegerProperty startPage = new SimpleIntegerProperty(); // 1-based, as users see it

        public ChapterRow(String title, int startPage) {
            this.title.set(title);
            this.startPage.set(startPage);
        }
        public StringProperty titleProperty() { return title; }
        public IntegerProperty startPageProperty() { return startPage; }
        public String getTitle() { return title.get(); }
        public int getStartPage() { return startPage.get(); }
    }

    private PDDocument doc;
    private PDFRenderer renderer;
    private int page = 0;
    private volatile int totalPages = 0;

    private final ImageView view = new ImageView();
    private final Label status = new Label("No file open");
    private final ObservableList<ChapterRow> rows = FXCollections.observableArrayList();
    private final TableView<ChapterRow> table = new TableView<>(rows);

    // One thread for all PDFBox work (PDDocument is not thread-safe)
    private final ExecutorService pdfThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });

    @Override
    public void start(Stage stage) {
        // ----- toolbar -----
        Button open = new Button("Open");
        Button prev = new Button("<");
        Button next = new Button(">");
        Button detect = new Button("Detect chapters");
        Button addRow = new Button("Add row");
        Button delRow = new Button("Delete row");
        Button split = new Button("Split...");
        Button fromToc = new Button("Detect from TOC text...");

        open.setOnAction(e -> {
            File f = new FileChooser().showOpenDialog(stage);
            if (f != null) load(f, stage);
        });
        prev.setOnAction(e -> show(page - 1));
        next.setOnAction(e -> show(page + 1));
        detect.setOnAction(e -> detectChapters());
        addRow.setOnAction(e -> rows.add(new ChapterRow("New chapter", page + 1)));
        delRow.setOnAction(e -> {
            ChapterRow sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) rows.remove(sel);
        });
        split.setOnAction(e -> splitChapters(stage));
        fromToc.setOnAction(e->detectFromTocText());

        ToolBar toolBar = new ToolBar(open, prev, next, new Separator(),
                detect, fromToc, addRow, delRow, split, new Separator(), status);

        // ----- chapter table -----
        table.setEditable(true);
        table.setPlaceholder(new Label("Click \"Detect chapters\""));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<ChapterRow, String> titleCol = new TableColumn<>("Title");
        titleCol.setCellValueFactory(d -> d.getValue().titleProperty());
        titleCol.setCellFactory(TextFieldTableCell.forTableColumn());

        TableColumn<ChapterRow, Integer> pageCol = new TableColumn<>("Start page");
        pageCol.setCellValueFactory(d -> d.getValue().startPageProperty().asObject());
        pageCol.setCellFactory(TextFieldTableCell.forTableColumn(new IntegerStringConverter()));
        pageCol.setMaxWidth(90);
        pageCol.setMinWidth(90);

        table.getColumns().addAll(titleCol, pageCol);

        // Selecting a row jumps the viewer to that chapter's first page
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, row) -> {
            if (row != null) show(row.getStartPage() - 1);
        });

        // ----- layout -----
        SplitPane center = new SplitPane(table, new ScrollPane(view));
        center.setDividerPositions(0.3);

        BorderPane root = new BorderPane();
        root.setTop(toolBar);
        root.setCenter(center);

        stage.setScene(new Scene(root, 1100, 750));
        stage.setTitle("PDF Splitter");
        stage.show();
    }

    // ----- PDF loading and rendering -----

    private void load(File f, Stage stage) {
        pdfThread.submit(() -> {
            try {
                if (doc != null) doc.close();
                doc = Loader.loadPDF(f);
                renderer = new PDFRenderer(doc);
                totalPages = doc.getNumberOfPages();
                Platform.runLater(() -> {
                    stage.setTitle(f.getName());
                    rows.clear();
                });
                show(0);
            } catch (Exception ex) {
                error("Could not open the PDF", ex);
            }
        });
    }

    private void show(int idx) {
        pdfThread.submit(() -> {
            if (doc == null || idx < 0 || idx >= doc.getNumberOfPages()) return;
            try {
                BufferedImage img = renderer.renderImageWithDPI(idx, 110);
                Image fx = SwingFXUtils.toFXImage(img, null);
                int total = doc.getNumberOfPages();
                Platform.runLater(() -> {
                    page = idx;
                    view.setImage(fx);
                    status.setText("Page " + (idx + 1) + " / " + total);
                });
            } catch (Exception ex) {
                error("Could not render page " + (idx + 1), ex);
            }
        });
    }

    // ----- chapters -----

    private void detectChapters() {
        pdfThread.submit(() -> {
            if (doc == null) {
                Platform.runLater(() -> info("Open a PDF first."));
                return;
            }
            try {
                List<Chapter> found = ChapterTools.fromOutline(doc);
                List<ChapterRow> newRows = new ArrayList<>();
                for (Chapter c : found) {
                    newRows.add(new ChapterRow(c.title(), c.startPage() + 1)); // 0-based -> 1-based
                }
                Platform.runLater(() -> {
                    rows.setAll(newRows);
                    if (newRows.isEmpty()) {
                        info("No bookmarks found in this PDF.\nAdd chapters manually with \"Add row\".");
                    }
                });
            } catch (Exception ex) {
                error("Chapter detection failed", ex);
            }
        });
    }

    private void splitChapters(Stage stage) {
        if (doc == null) { info("Open a PDF first."); return; }
        if (rows.isEmpty()) { info("There are no chapters to split."); return; }

        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose output folder");
        File dir = chooser.showDialog(stage);
        if (dir == null) return;

        // Copy the table into plain records here, on the UI thread
        List<Chapter> chapters = new ArrayList<>();
        for (ChapterRow r : rows) {
            chapters.add(new Chapter(r.getTitle(), r.getStartPage() - 1)); // 1-based -> 0-based
        }

        Platform.runLater(() -> status.setText("Splitting..."));
        pdfThread.submit(() -> {
            try {
                ChapterTools.split(doc, chapters, dir.toPath());
                Platform.runLater(() -> {
                    status.setText("Done");
                    info("Saved " + chapters.size() + " chapter files to:\n" + dir);
                });
            } catch (Exception ex) {
                error("Split failed", ex);
            }
        });
    }

    // ----- dialogs (safe to call from any thread) -----

    private void info(String msg) {
        Platform.runLater(() -> new Alert(Alert.AlertType.INFORMATION, msg).showAndWait());
    }

    private void error(String msg, Exception ex) {
        ex.printStackTrace();
        Platform.runLater(() ->
                new Alert(Alert.AlertType.ERROR, msg + ":\n" + ex.getMessage()).showAndWait());
    }

    private record TocParams(int first, int last, int printedOneOnPdfPage, boolean auto, String filter) {}

    private Optional<TocParams> askTocParams() {
        int max = Math.max(1, totalPages);
        int cur = Math.min(page + 1, max);

        Spinner<Integer> first = new Spinner<>(1, max, cur);
        Spinner<Integer> last = new Spinner<>(1, max, cur);
        Spinner<Integer> printedOne = new Spinner<>(1, max, 1);
        for (Spinner<Integer> s : List.of(first, last, printedOne)) {
            s.setEditable(true);
            s.setPrefWidth(90);
        }
        CheckBox auto = new CheckBox("Auto-detect the page offset (ignores the field above)");
        auto.setSelected(true);
        TextField filter = new TextField();
        filter.setPromptText("optional regex, e.g.  ^(chapter|part)\\b");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.addRow(0, new Label("TOC first PDF page:"), first);
        grid.addRow(1, new Label("TOC last PDF page:"), last);
        grid.addRow(2, new Label("Printed page 1 is PDF page:"), printedOne);
        grid.add(auto, 0, 3, 2, 1);
        grid.addRow(4, new Label("Only keep titles matching:"), filter);

        Dialog<TocParams> d = new Dialog<>();
        d.setTitle("Detect chapters from TOC text");
        d.setHeaderText("Tip: go to the first TOC page in the viewer before opening this.");
        d.getDialogPane().setContent(grid);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            first.increment(0);        // commits any text typed into the spinners
            last.increment(0);
            printedOne.increment(0);
            return new TocParams(first.getValue(), last.getValue(), printedOne.getValue(),
                    auto.isSelected(), filter.getText().trim());
        });
        return d.showAndWait();
    }

    private void detectFromTocText() {
        if (doc == null) { info("Open a PDF first."); return; }
        Optional<TocParams> asked = askTocParams();
        if (asked.isEmpty()) return;
        TocParams tp = asked.get();
        if (tp.last() < tp.first()) {
            info("The last TOC page can't come before the first one.");
            return;
        }

        status.setText("Reading TOC...");
        pdfThread.submit(() -> {
            try {
                ChapterTools.DetectedToc toc =
                        ChapterTools.detectTopLevel(doc, tp.first() - 1, tp.last() - 1);
                List<ChapterTools.TocEntry> entries = toc.entries();

                // Offset only matters for entries that have printed page numbers
                int offset = tp.printedOneOnPdfPage() - 1;
                String how = "manual offset";
                List<Chapter> printed = new ArrayList<>();
                for (ChapterTools.TocEntry e : entries) {
                    if (e.printedPage() != null) printed.add(new Chapter(e.title(), e.printedPage() - 1));
                }
                if (tp.auto() && !printed.isEmpty()) {
                    Integer guess = ChapterTools.guessOffset(doc, printed, tp.last() - 1);
                    if (guess != null) { offset = guess; how = "auto-detected offset"; }
                    else how = "auto-detect failed, used manual offset";
                } else if (printed.isEmpty()) {
                    how = "located by title search";
                }
                final int usedOffset = offset;
                final String usedHow = how;

                List<Chapter> found = ChapterTools.resolve(doc, entries, tp.last() - 1, usedOffset);
                List<ChapterRow> newRows = new ArrayList<>();
                for (Chapter c : found) newRows.add(new ChapterRow(c.title(), c.startPage() + 1));
                newRows.sort(Comparator.comparingInt(ChapterRow::getStartPage));
                final int skipped = entries.size() - found.size();

                Platform.runLater(() -> {
                    rows.setAll(newRows);
                    status.setText("TOC: " + newRows.size() + " chapters (" + usedHow + ")");
                    if (newRows.isEmpty()) {
                        info("No top-level lines found.\nCheck the console: lines marked MATCH were accepted.");
                    } else if (skipped > 0) {
                        info(skipped + " entries couldn't be placed in the document and were skipped.");
                    }
                });
            } catch (Exception ex) {
                error("TOC detection failed", ex);
            }
        });
    }

    @Override
    public void stop() throws Exception {
        pdfThread.shutdownNow();
        if (doc != null) doc.close();
    }

    public static void main(String[] args) {
        launch();
    }
}
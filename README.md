# PDF Splitter

A desktop app that splits a PDF, such as a textbook, into one file per chapter. It reads the chapter list from the PDF's bookmarks or from its printed table of contents, lets you correct the list, and saves each chapter as its own PDF.

[https://github.com/user-attachments/assets/5182fcc7-54ef-42ff-873c-e521927eba5a]


## Download

Get the latest installer for your system from the [Releases page](https://github.com/kureousone-netizen/PDFSplitter/releases):

| System | File |
|---|---|
| Windows | `.msi` |
| macOS (Apple Silicon) | `.dmg` |
| Linux (Debian/Ubuntu) | `.deb` |

You don't need to install Java. It's bundled with the installer.

### First-run warnings

The installers aren't code-signed, so your system may warn you the first time:

- **Windows:** SmartScreen shows "Windows protected your PC". Click **More info**, then **Run anyway**.
- **macOS:** Gatekeeper blocks the app. Right-click the app, choose **Open**, then confirm.

## How to use it

### 1. Open a PDF

Click **Open** and choose your PDF. Use `<` and `>` to flip through pages.

### 2. Find the chapters

Try these in order, stopping when the table looks right.

**a. Detect chapters (bookmarks).** Click **Detect chapters**. If the PDF has embedded bookmarks, the table fills instantly with accurate titles and start pages. This is the most reliable method, so always try it first. Only top-level bookmarks are used.

**b. Detect from TOC text.** If there are no bookmarks (the app offers this automatically), click **Detect from TOC text...**:

1. Flip to the first page of the printed table of contents in the viewer.
2. In the dialog, set the first and last PDF pages of the TOC.
3. Leave **Auto-detect the page offset** ticked. If chapters land on the wrong pages, untick it and enter the PDF page where the book's printed page 1 sits. For example, if printed page 1 is the 13th page in the viewer, enter 13.
4. Click **OK**. The app tries several common TOC layouts (`1 Title ..... 19`, `1. Title ..... 19`, `Chapter 1 Title ..... 19`) and uses the one that produces a clean, in-order list. The status bar shows which pattern it picked.

**c. Add rows manually.** If neither method finds everything, or the PDF is a scan with no text, build or fix the list by hand:

- Go to the first page of a chapter in the viewer and click **Add row**. The new row uses the current page.
- Double-click a **Title** or **Start page** cell to edit it.
- Select a row and click **Delete row** to remove it.

You can mix methods: detect automatically, then delete wrong rows and add missing ones.

### 3. Check the list

Click any row in the table and the viewer jumps to that chapter's start page. Spot-check a few chapters to confirm they begin where they should.

### 4. Split

Click **Split...**, choose an output folder, and the app writes one file per chapter, named like:

```
01 - Introduction.pdf
02 - Intelligent Agents.pdf
```

Each chapter runs from its start page to the page before the next chapter. The last chapter runs to the end of the document, so back matter (bibliography, index) ends up in the last file unless you add a row for it.

## Limitations

- Scanned PDFs (images with no text) can't be read by the TOC detection. Use manual rows.
- TOC entries whose titles wrap onto two lines, and two-column TOCs, may not be detected.
- Roman-numeral and lettered chapters (for example appendices `A`, `B`) aren't picked up automatically. Add them as rows.
- Only top-level chapters are detected, not sub-sections.

## Building from source

Requires JDK 21.

```bash
./mvnw clean package
java -jar target/PDFSplitter-1.0-SNAPSHOT.jar
```

During development you can run it with `./mvnw javafx:run`.

Installers are built with `jpackage` by the GitHub Actions workflow in `.github/workflows/build-installers.yml`.

## Built with

Java 21, JavaFX, and Apache PDFBox.

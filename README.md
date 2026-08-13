# Intelligent Toll Fare Extractor

This Spring Boot application automates toll fare data extraction using Generative AI. It eliminates manual data entry by parsing unstructured fare charts from PDFs and images. The backend leverages Apache POI to read existing Excel templates and extracts authoritative vehicle classifications. Spring AI then processes the documents, intelligently mapping extracted fares to the correct vehicle categories using strict JSON schemas and semantic matching. Finally, a modern frontend displays this AI-mapped data in an editable, responsive spreadsheet grid. This allows for quick human verification and seamless exporting to updated Excel or CSV files, ensuring maximum accuracy and drastically accelerating workflow efficiency.

## Features

- **Automated PDF/Image Parsing**: Upload standard IHMCL fare charts and extract the relevant tabular data using Vision AI.
- **Strict AVC_ID Mapping**: Guarantees that AI-extracted fares are mapped to your authoritative system vehicle categories, avoiding hallucinated or mismatched IDs.
- **Interactive Spreadsheet UI**: Review and edit the extracted data directly in the browser with an Excel-like experience (powered by Tabulator).
- **Direct Excel/CSV Export**: Download the verified data straight back into standard spreadsheet formats.
- **Zero Frontend Logic**: All complex parsing and validation happens securely on the Java backend.

## Tech Stack

- **Backend**: Java 21, Spring Boot 3.3.x, Spring Web
- **AI Integration**: Spring AI
- **Excel Parsing**: Apache POI (`poi-ooxml`)
- **Frontend**: Vanilla HTML/JS/CSS, Tabulator (for spreadsheet UI), SheetJS

## Prerequisites

- Java 21 or higher
- Maven 3.8+
- Active AI Model API Key (configured in `application.properties` or environment variables)

## Getting Started

1. **Clone the repository:**
   ```bash
   git clone <your-repo-url>
   cd fare-extraction
   ```

2. **Configure AI Properties:**
   Ensure your API key is correctly configured for Spring AI inside `src/main/resources/application.properties`.
   ```properties
   spring.ai.openai.api-key=${OPENAI_API_KEY}
   # or whichever model provider you are using
   ```

3. **Build and Run:**
   ```bash
   ./mvnw clean install
   ./mvnw spring-boot:run
   ```

4. **Access the Application:**
   Open your browser and navigate to:
   ```text
   http://localhost:8080
   ```

## How to Use

1. **Upload AVC Template**: Drag and drop your `.xlsx` or `.xls` template containing the authoritative `AVC_ID` and `VEHICLE_DESCS` columns.
2. **Upload Fare Chart**: Drag and drop the official fare chart document (PDF or Image).
3. **Extract & Map**: Click the button to send the files to the backend. The AI will extract the fares and perfectly align them with your template.
4. **Verify & Edit**: The results will appear in a standard spreadsheet grid. If the AI made a slight error or missed a footnote, you can click on any cell to edit it manually.
5. **Download**: Click "Download Excel" or "Download CSV" to save the finalized template to your computer.

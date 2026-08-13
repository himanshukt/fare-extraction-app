document.addEventListener('DOMContentLoaded', () => {
    const excelDropZone = document.getElementById('excel-drop-zone');
    const pdfDropZone = document.getElementById('pdf-drop-zone');
    const excelFileInput = document.getElementById('excel-file');
    const pdfFileInput = document.getElementById('pdf-file');
    const excelStatus = document.getElementById('excel-status');
    const pdfStatus = document.getElementById('pdf-status');
    const processBtn = document.getElementById('process-btn');
    const downloadBtn = document.getElementById('download-btn');
    const loadingOverlay = document.getElementById('loading-overlay');
    const resultsSection = document.getElementById('results-section');
    const plazaInfo = document.getElementById('plaza-info');

    let excelFile = null;
    let pdfFile = null;
    let table = null;

    const COL = {
        ENTRY_PLAZA_ID:      'ENTRY_PLAZA_ID',
        EXIT_PLAZA_ID:       'EXIT_PLAZA_ID',
        AVC_ID:              'AVC_ID',
        MVC_IDS:             'MVC_IDS',
        VEHICLE_DESCS:       'VEHICLE_DESCS',
        SINGLE_JOURNEY_FARE: 'SINGLE_JOURNEY_FARE',
        RETURN_JOURNEY_FARE: 'RETURN_JOURNEY_FARE',
        COM_VEHICLE_FARE:    'COM_VEHICLE_FARE',
        MONTHLY_PASS_FARE:   'MONTHLY_PASS_FARE',
        LOCAL20_PASS_FARE:   'LOCAL20_PASS_FARE',
    };

    // --- Drag & Drop ---
    function setupDropZone(dropZone, fileInput, statusEl, setter) {
        dropZone.addEventListener('click', () => fileInput.click());
        dropZone.addEventListener('dragover', (e) => { e.preventDefault(); dropZone.classList.add('dragover'); });
        dropZone.addEventListener('dragleave', () => dropZone.classList.remove('dragover'));
        dropZone.addEventListener('drop', (e) => {
            e.preventDefault();
            dropZone.classList.remove('dragover');
            if (e.dataTransfer.files.length > 0) pickFile(e.dataTransfer.files[0], statusEl, setter);
        });
        fileInput.addEventListener('change', (e) => {
            if (e.target.files.length > 0) pickFile(e.target.files[0], statusEl, setter);
        });
    }

    function pickFile(file, statusEl, setter) {
        setter(file);
        statusEl.textContent = file.name;
        statusEl.style.display = 'block';
        if (excelFile && pdfFile) processBtn.disabled = false;
    }

    setupDropZone(excelDropZone, excelFileInput, excelStatus, (f) => excelFile = f);
    setupDropZone(pdfDropZone, pdfFileInput, pdfStatus, (f) => pdfFile = f);

    // --- Process: Send BOTH files to backend, render response directly ---
    processBtn.addEventListener('click', async () => {
        if (!excelFile || !pdfFile) return;

        loadingOverlay.classList.remove('hidden');
        resultsSection.classList.add('hidden');

        try {
            const formData = new FormData();
            formData.append('template', excelFile);
            formData.append('fareChart', pdfFile);

            const response = await fetch('/api/v1/extract-and-map', {
                method: 'POST',
                body: formData
            });

            const result = await response.json();

            if (!response.ok) {
                throw new Error(result.error || 'Failed to extract fares.');
            }

            console.log('Backend response:', JSON.stringify(result, null, 2));

            const rows = result.rows || [];
            if (rows.length === 0) throw new Error('No fare rows returned.');

            // Show warnings
            if (result.warnings && result.warnings.length > 0) {
                console.warn('Warnings:', result.warnings);
                plazaInfo.textContent = '⚠️ ' + result.warnings.length + ' warning(s) — check console';
            }

            // Render directly — NO mapping, NO transformation
            renderGrid(rows);

        } catch (error) {
            alert('Error: ' + error.message);
            console.error(error);
        } finally {
            loadingOverlay.classList.add('hidden');
        }
    });

    // --- Render Grid ---
    function renderGrid(data) {
        const columns = [
            { title: 'Entry Plaza',    field: COL.ENTRY_PLAZA_ID,      editor: 'input', width: 120 },
            { title: 'Exit Plaza',     field: COL.EXIT_PLAZA_ID,       editor: 'input', width: 120 },
            { title: 'AVC ID',         field: COL.AVC_ID,              editor: 'input', width: 80,  hozAlign: 'center' },
            { title: 'MVC IDs',        field: COL.MVC_IDS,             editor: 'input', width: 100 },
            { title: 'Vehicle Desc',   field: COL.VEHICLE_DESCS,       editor: 'input', minWidth: 200 },
            { title: 'Single (₹)',     field: COL.SINGLE_JOURNEY_FARE, editor: 'input', width: 110, hozAlign: 'right' },
            { title: 'Return (₹)',     field: COL.RETURN_JOURNEY_FARE, editor: 'input', width: 110, hozAlign: 'right' },
            { title: 'Commercial (₹)', field: COL.COM_VEHICLE_FARE,    editor: 'input', width: 130, hozAlign: 'right' },
            { title: 'Monthly (₹)',    field: COL.MONTHLY_PASS_FARE,   editor: 'input', width: 120, hozAlign: 'right' },
            { title: 'Local 20 (₹)',   field: COL.LOCAL20_PASS_FARE,   editor: 'input', width: 120, hozAlign: 'right' },
        ];

        if (table) table.destroy();

        table = new Tabulator('#data-grid', {
            data: data,
            layout: 'fitColumns',
            responsiveLayout: 'collapse',
            columns: columns,
            history: true,
        });

        resultsSection.classList.remove('hidden');
    }

    // --- Download Excel ---
    downloadBtn.addEventListener('click', () => {
        if (!table) return;

        const finalData = table.getData();
        const headerOrder = [
            COL.ENTRY_PLAZA_ID, COL.EXIT_PLAZA_ID, COL.AVC_ID, COL.MVC_IDS, COL.VEHICLE_DESCS,
            COL.SINGLE_JOURNEY_FARE, COL.RETURN_JOURNEY_FARE, COL.COM_VEHICLE_FARE,
            COL.MONTHLY_PASS_FARE, COL.LOCAL20_PASS_FARE,
        ];

        const aoa = [headerOrder];
        finalData.forEach(row => {
            aoa.push(headerOrder.map(col => {
                const val = row[col];
                return (val !== undefined && val !== null) ? val : '';
            }));
        });

        const worksheet = XLSX.utils.aoa_to_sheet(aoa);
        worksheet['!cols'] = [
            { wch: 14 }, { wch: 14 }, { wch: 8 }, { wch: 10 }, { wch: 40 },
            { wch: 20 }, { wch: 20 }, { wch: 18 }, { wch: 18 }, { wch: 18 },
        ];

        const workbook = XLSX.utils.book_new();
        XLSX.utils.book_append_sheet(workbook, worksheet, 'Fare Data');
        XLSX.writeFile(workbook, 'Updated_Fare_Template.xlsx');
    });

    // --- Download CSV ---
    const downloadCsvBtn = document.getElementById('download-csv-btn');
    if (downloadCsvBtn) {
        downloadCsvBtn.addEventListener('click', () => {
            if (!table) return;

            const finalData = table.getData();
            const headerOrder = [
                COL.ENTRY_PLAZA_ID, COL.EXIT_PLAZA_ID, COL.AVC_ID, COL.MVC_IDS, COL.VEHICLE_DESCS,
                COL.SINGLE_JOURNEY_FARE, COL.RETURN_JOURNEY_FARE, COL.COM_VEHICLE_FARE,
                COL.MONTHLY_PASS_FARE, COL.LOCAL20_PASS_FARE,
            ];

            let csv = headerOrder.join(',') + '\n';
            finalData.forEach(row => {
                csv += headerOrder.map(col => {
                    const val = row[col];
                    if (val === undefined || val === null) return '';
                    const str = String(val);
                    return str.includes(',') ? `"${str}"` : str;
                }).join(',') + '\n';
            });

            const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
            const link = document.createElement('a');
            link.href = URL.createObjectURL(blob);
            link.download = 'Updated_Fare_Template.csv';
            link.click();
        });
    }
});

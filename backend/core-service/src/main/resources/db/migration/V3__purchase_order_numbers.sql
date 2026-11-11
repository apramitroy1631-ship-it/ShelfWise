-- Purchase-order numbers for POs created in the app.
-- Imported POs use PO-0001 .. PO-0300; app POs start at PO-10000 so the two never meet.
CREATE SEQUENCE po_number_seq START WITH 10000;

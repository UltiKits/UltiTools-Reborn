package com.ultikits.testfixtures.jsontx.ordinary;

import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.abstracts.data.BaseDataEntity;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Column;
import com.ultikits.ultitools.annotations.PostConstruct;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.annotations.Table;
import com.ultikits.ultitools.annotations.UltiToolsModule;
import com.ultikits.ultitools.interfaces.DataOperator;

/**
 * An ordinary JSON module with no {@code @Transactional}, loaded beside
 * {@code JsonTxTransactionalModule} by {@code JsonTransactionalModuleTest} (Phase 20.4, plan 10).
 * It scans only this package; every class it needs is a static nested class of this one.
 */
@UltiToolsModule(scanBasePackages = "com.ultikits.testfixtures.jsontx.ordinary")
public class JsonTxOrdinaryModule extends UltiToolsPlugin {

    @Override
    public boolean registerSelf() {
        return true;
    }

    /** The module's only table. */
    @Table("jsontx_notes")
    public static class NoteRow extends BaseDataEntity<String> {
        private static final long serialVersionUID = 1L;

        @Column("text")
        private String text;

        /** For deserialization. */
        public NoteRow() {
        }

        /**
         * @param id   the note id
         * @param text the note text
         */
        public NoteRow(String id, String text) {
            setId(id);
            this.text = text;
        }

        /** @return the note text */
        public String getText() {
            return text;
        }
    }

    /** A plain service: inserts and reads notes outside any transaction. */
    @Service
    public static class NoteService {

        @Autowired
        private UltiToolsPlugin plugin;

        private DataOperator<NoteRow> notes;

        @PostConstruct
        void openOperator() {
            notes = plugin.getDataOperator(NoteRow.class);
        }

        /**
         * @param id   the note id
         * @param text the note text
         */
        public void write(String id, String text) {
            notes.insert(new NoteRow(id, text));
        }

        /**
         * @param id the note id
         * @return the note text, or {@code null} when no note is stored under {@code id}
         */
        public String read(String id) {
            NoteRow row = notes.getById(id);
            return row == null ? null : row.getText();
        }
    }
}

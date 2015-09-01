package de.rwth.idsg.steve.repository;

import de.rwth.idsg.steve.SteveException;
import de.rwth.idsg.steve.repository.dto.Reservation;
import de.rwth.idsg.steve.utils.DateTimeUtils;
import de.rwth.idsg.steve.web.dto.ReservationQueryForm;
import jooq.steve.db.tables.records.ReservationRecord;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Configuration;
import org.jooq.RecordMapper;
import org.jooq.SelectQuery;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

import static de.rwth.idsg.steve.utils.DateTimeUtils.toTimestamp;
import static jooq.steve.db.tables.Reservation.RESERVATION;

/**
 * @author Sevket Goekay <goekay@dbis.rwth-aachen.de>
 * @since 14.08.2014
 */
@Slf4j
@Repository
public class ReservationRepositoryImpl implements ReservationRepository {

    @Autowired
    @Qualifier("jooqConfig")
    private Configuration config;

    @Override
    @SuppressWarnings("unchecked")
    public List<Reservation> getReservations(ReservationQueryForm form) {
        SelectQuery selectQuery = DSL.using(config).selectQuery();
        selectQuery.addFrom(RESERVATION);
        selectQuery.addSelect(RESERVATION.fields());

        if (form.isChargeBoxIdSet()) {
            selectQuery.addConditions(RESERVATION.CHARGEBOXID.eq(form.getChargeBoxId()));
        }

        if (form.isUserIdSet()) {
            selectQuery.addConditions(RESERVATION.IDTAG.eq(form.getUserId()));
        }

        if (form.isStatusSet()) {
            selectQuery.addConditions(RESERVATION.STATUS.eq(form.getStatus().name()));
        }

        processType(selectQuery, form);

        // Default order
        selectQuery.addOrderBy(RESERVATION.EXPIRYDATETIME.asc());

        return selectQuery.fetch().map(new ReservationMapper());
    }

    /**
     * SELECT reservation_pk
     * FROM reservation
     * WHERE chargeBoxId = ?
     * AND expiryDatetime > CURRENT_TIMESTAMP AND status = ?
     */
    @Override
    public List<Integer> getActiveReservationIds(String chargeBoxId) {
        return DSL.using(config)
                  .select(RESERVATION.RESERVATION_PK)
                  .from(RESERVATION)
                  .where(RESERVATION.CHARGEBOXID.equal(chargeBoxId))
                        .and(RESERVATION.EXPIRYDATETIME.greaterThan(DSL.currentTimestamp()))
                        .and(RESERVATION.STATUS.equal(ReservationStatus.ACCEPTED.name()))
                  .fetch(RESERVATION.RESERVATION_PK);
    }

    /**
     * INSERT INTO reservation (idTag, chargeBoxId, startDatetime, expiryDatetime, status) VALUES (?,?,?,?,?)
     */
    @Override
    public int insert(String idTag, String chargeBoxId, Timestamp startTimestamp, Timestamp expiryTimestamp) {
        // Check overlapping
        //isOverlapping(startTimestamp, expiryTimestamp, chargeBoxId);

        int reservationId = DSL.using(config)
                               .insertInto(RESERVATION,
                                       RESERVATION.IDTAG, RESERVATION.CHARGEBOXID,
                                       RESERVATION.STARTDATETIME, RESERVATION.EXPIRYDATETIME,
                                       RESERVATION.STATUS)
                               .values(idTag, chargeBoxId,
                                       startTimestamp, expiryTimestamp,
                                       ReservationStatus.WAITING.name())
                               .returning(RESERVATION.RESERVATION_PK)
                               .fetchOne()
                               .getReservationPk();

        log.debug("A new reservation '{}' is inserted.", reservationId);
        return reservationId;
    }

    /**
     * DELETE FROM reservation
     * WHERE reservation_pk = ?
     */
    @Override
    public void delete(int reservationId) {
        DSL.using(config)
           .delete(RESERVATION)
           .where(RESERVATION.RESERVATION_PK.equal(reservationId))
           .execute();

        log.debug("The reservation '{}' is deleted.", reservationId);
    }

    @Override
    public void accepted(int reservationId) {
        internalUpdateReservation(reservationId, ReservationStatus.ACCEPTED);
    }

    @Override
    public void cancelled(int reservationId) {
        internalUpdateReservation(reservationId, ReservationStatus.CANCELLED);
    }

    /**
     * UPDATE reservation
     * SET status = ?,
     * SET transaction_pk = ?
     * WHERE reservation_pk = ?
     */
    @Override
    public void used(int reservationId, int transactionId) {
        DSL.using(config)
           .update(RESERVATION)
           .set(RESERVATION.STATUS, ReservationStatus.USED.name())
           .set(RESERVATION.TRANSACTION_PK, transactionId)
           .where(RESERVATION.RESERVATION_PK.equal(reservationId))
           .execute();
    }

    /**
     * UPDATE reservation
     * SET status = ?
     * WHERE reservation_pk = ?
     */
    private void internalUpdateReservation(int reservationId, ReservationStatus status) {
        try {
            DSL.using(config)
               .update(RESERVATION)
               .set(RESERVATION.STATUS, status.name())
               .where(RESERVATION.RESERVATION_PK.equal(reservationId))
               .execute();
        } catch (DataAccessException e) {
            log.error("Updating of reservationId '{}' to status '{}' FAILED.", reservationId, status, e);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private class ReservationMapper implements RecordMapper<ReservationRecord, Reservation> {
        @Override
        public Reservation map(ReservationRecord r) {
            return Reservation.builder()
                    .id(r.getReservationPk())
                    .transactionId(r.getTransactionPk())
                    .idTag(r.getIdtag())
                    .chargeBoxId(r.getChargeboxid())
                    .startDatetime(DateTimeUtils.humanize(r.getStartdatetime()))
                    .expiryDatetime(DateTimeUtils.humanize(r.getExpirydatetime()))
                    .status(r.getStatus())
                    .build();
        }
    }

    private void processType(SelectQuery selectQuery, ReservationQueryForm form) {
        switch (form.getPeriodType()) {
            case ACTIVE:
                selectQuery.addConditions(RESERVATION.EXPIRYDATETIME.greaterThan(DSL.currentTimestamp()));
                break;

            case FROM_TO:
                selectQuery.addConditions(
                        RESERVATION.STARTDATETIME.greaterOrEqual(toTimestamp(form.getFrom())),
                        RESERVATION.EXPIRYDATETIME.lessOrEqual(toTimestamp(form.getTo()))
                );
                break;
        }
    }

    /**
     * Throws exception, if there are rows whose date/time ranges overlap with the input
     *
     * This WHERE clause covers all three cases:
     *
     * SELECT 1
     * FROM reservation
     * WHERE ? <= expiryDatetime AND ? >= startDatetime AND chargeBoxId = ?
     */
    private void isOverlapping(Timestamp start, Timestamp stop, String chargeBoxId) {
        try {
            int count = DSL.using(config)
                           .selectOne()
                           .from(RESERVATION)
                           .where(RESERVATION.EXPIRYDATETIME.greaterOrEqual(start))
                             .and(RESERVATION.STARTDATETIME.lessOrEqual(stop))
                             .and(RESERVATION.CHARGEBOXID.equal(chargeBoxId))
                           .execute();

            if (count != 1) {
                throw new SteveException("The desired reservation overlaps with another reservation");
            }

        } catch (DataAccessException e) {
            log.error("Exception occurred", e);
        }
    }
}
/*
 * mxisd - Matrix Identity Server Daemon
 * Copyright (C) 2018 Kamax Sarl
 *
 * https://www.kamax.io/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.kamax.mxisd.as;

import com.google.gson.JsonObject;
import io.kamax.matrix.MatrixID;
import io.kamax.matrix.ThreePidMedium;
import io.kamax.matrix._MatrixID;
import io.kamax.matrix._ThreePid;
import io.kamax.matrix.event.EventKey;
import io.kamax.matrix.json.GsonUtil;
import io.kamax.mxisd.backend.sql.synapse.Synapse;
import io.kamax.mxisd.config.MatrixConfig;
import io.kamax.mxisd.config.MxisdConfig;
import io.kamax.mxisd.exception.HttpMatrixException;
import io.kamax.mxisd.exception.NotAllowedException;
import io.kamax.mxisd.notification.NotificationManager;
import io.kamax.mxisd.profile.ProfileManager;
import io.kamax.mxisd.storage.IStorage;
import io.kamax.mxisd.storage.ormlite.dao.ASTransactionDao;
import io.kamax.mxisd.util.GsonParser;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class AppSvcManager {

    private transient final Logger log = LoggerFactory.getLogger(AppSvcManager.class);

    private final GsonParser parser;

    private MatrixConfig cfg;
    private IStorage store;
    private ProfileManager profiler;
    private NotificationManager notif;
    private Synapse synapse;

    private Map<String, CompletableFuture<String>> transactionsInProgress;

    public AppSvcManager(MxisdConfig cfg, IStorage store, ProfileManager profiler, NotificationManager notif, Synapse synapse) {
        this.cfg = cfg.getMatrix();
        this.store = store;
        this.profiler = profiler;
        this.notif = notif;
        this.synapse = synapse;

        parser = new GsonParser();
        transactionsInProgress = new ConcurrentHashMap<>();
    }

    public AppSvcManager withToken(String token) {
        if (StringUtils.isBlank(token)) {
            throw new HttpMatrixException(401, "M_UNAUTHORIZED", "No HS token");
        }

        if (!StringUtils.equals(cfg.getListener().getToken().getHs(), token)) {
            throw new NotAllowedException("Invalid HS token");
        }

        return this;
    }

    public CompletableFuture<String> processTransaction(String txnId, InputStream is) {
        if (StringUtils.isEmpty(txnId)) {
            throw new IllegalArgumentException("Transaction ID cannot be empty");
        }

        synchronized (this) {
            Optional<ASTransactionDao> dao = store.getTransactionResult(cfg.getListener().getLocalpart(), txnId);
            if (dao.isPresent()) {
                log.info("AS Transaction {} already processed - returning computed result", txnId);
                return CompletableFuture.completedFuture(dao.get().getResult());
            }

            CompletableFuture<String> f = transactionsInProgress.get(txnId);
            if (Objects.nonNull(f)) {
                log.info("Returning future for transaction {}", txnId);
                return f;
            }

            transactionsInProgress.put(txnId, new CompletableFuture<>());
        }

        CompletableFuture<String> future = transactionsInProgress.get(txnId);

        Instant start = Instant.now();
        log.info("Processing AS Transaction {}: start", txnId);
        try {
            List<JsonObject> events = GsonUtil.asList(GsonUtil.getArray(parser.parse(is), "events"), JsonObject.class);
            is.close();
            log.debug("{} event(s) parsed", events.size());

            processTransaction(events);

            Instant end = Instant.now();
            String result = "{}";

            try {
                log.info("Saving transaction details to store");
                store.insertTransactionResult(cfg.getListener().getLocalpart(), txnId, end, result);
            } finally {
                log.debug("Removing CompletedFuture from transaction map");
                transactionsInProgress.remove(txnId);
            }

            log.info("Processed AS transaction {} in {} ms", txnId, (Instant.now().toEpochMilli() - start.toEpochMilli()));
            future.complete(result);
        } catch (Exception e) {
            log.error("Unable to properly process transaction {}", txnId, e);
            future.completeExceptionally(e);
        }

        log.info("Processing AS Transaction {}: end", txnId);
        return future;
    }

    public void processTransaction(List<JsonObject> eventsJson) {
        log.info("Processing transaction events: start");

        eventsJson.forEach(ev -> {
            String evId = EventKey.Id.getStringOrNull(ev);
            if (StringUtils.isBlank(evId)) {
                log.warn("Event has no ID, skipping");
                log.debug("Event:\n{}", GsonUtil.getPrettyForLog(ev));
                return;
            }
            log.debug("Event {}: processing start", evId);

            String roomId = EventKey.RoomId.getStringOrNull(ev);
            if (StringUtils.isBlank(roomId)) {
                log.debug("Event has no room ID, skipping");
                return;
            }

            String senderId = EventKey.Sender.getStringOrNull(ev);
            if (StringUtils.isBlank(senderId)) {
                log.debug("Event has no sender ID, skipping");
                return;
            }
            _MatrixID sender = MatrixID.asAcceptable(senderId);
            log.debug("Sender: {}", senderId);

            if (!StringUtils.equals("m.room.member", GsonUtil.getStringOrNull(ev, "type"))) {
                log.debug("This is not a room membership event, skipping");
                return;
            }

            JsonObject content = EventKey.Content.findObj(ev).orElseGet(() -> {
                log.debug("No content found, falling back to full object");
                return ev;
            });

            if (!StringUtils.equals("invite", GsonUtil.getStringOrNull(content, "membership"))) {
                log.debug("This is not an invite event, skipping");
                return;
            }

            String inviteeId = EventKey.StateKey.getStringOrNull(ev);
            if (StringUtils.isBlank(inviteeId)) {
                log.warn("Invalid event: No invitee ID, skipping");
                return;
            }

            _MatrixID invitee = MatrixID.asAcceptable(inviteeId);
            if (!StringUtils.equals(invitee.getDomain(), cfg.getDomain())) {
                log.debug("Ignoring invite for {}: not a local user");
                return;
            }

            log.info("Got invite from {} to {}", senderId, inviteeId);

            boolean wasSent = false;
            List<_ThreePid> tpids = profiler.getThreepids(invitee).stream()
                    .filter(tpid -> ThreePidMedium.Email.is(tpid.getMedium()))
                    .collect(Collectors.toList());
            log.info("Found {} email(s) in identity store for {}", tpids.size(), inviteeId);

            for (_ThreePid tpid : tpids) {
                log.info("Found Email to notify about room invitation: {}", tpid.getAddress());
                Map<String, String> properties = new HashMap<>();
                profiler.getDisplayName(sender).ifPresent(name -> properties.put("sender_display_name", name));
                try {
                    synapse.getRoomName(roomId).ifPresent(name -> properties.put("room_name", name));
                } catch (RuntimeException e) {
                    log.warn("Could not fetch room name", e);
                    log.info("Unable to fetch room name: Did you integrate your Homeserver as documented?");
                }

                IMatrixIdInvite inv = new MatrixIdInvite(roomId, sender, invitee, tpid.getMedium(), tpid.getAddress(), properties);
                notif.sendForInvite(inv);
                log.info("Notification for invite of {} sent to {}", inviteeId, tpid.getAddress());
                wasSent = true;
            }

            log.info("Was notification sent? {}", wasSent);

            log.debug("Event {}: processing end", evId);
        });

        log.info("Processing transaction events: end");
    }

}

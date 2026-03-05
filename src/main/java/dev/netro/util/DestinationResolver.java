package dev.netro.util;

import dev.netro.database.StationRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.model.Station;

import java.util.Optional;

/**
 * Resolves destination strings to full addresses for cart routing.
 * Supports: new-format 6/7-part address (OV:E2:N3:01:02:05 or with terminal), station name (e.g. Snowy2),
 * Name:TerminalIndex (e.g. Snowy2:0), and Name:TerminalName (e.g. Snowy2:Platform A). Terminal indices are 0-based.
 * Legacy 1D numeric addresses are not returned (only stations found by address in DB, which use new format).
 */
public final class DestinationResolver {

    private DestinationResolver() {}

    /**
     * Resolves a destination string to a full address (new-format 6-part or 7-part with terminal).
     *
     * @param stationRepo station repository
     * @param nodeRepo    transfer node repository (for terminal-by-index)
     * @param input      new address (OV:…), station name, or Name:Node/Index
     * @return the full address to set as destination, or empty if not found / legacy format
     */
    public static Optional<String> resolveToAddress(StationRepository stationRepo,
                                                     TransferNodeRepository nodeRepo,
                                                     String input) {
        if (input == null || input.isBlank()) return Optional.empty();
        String s = input.strip();

        if (AddressHelper.parseDestination(s).isPresent()) {
            return Optional.of(s);
        }

        if (s.matches("[0-9.]+")) {
            return stationRepo.findByAddress(s).map(Station::getAddress);
        }

        int colon = s.indexOf(':');
        if (colon >= 0) {
            String namePart = s.substring(0, colon).strip();
            String indexPart = s.substring(colon + 1).strip();
            Optional<Station> station = stationRepo.findByNameIgnoreCase(namePart)
                .or(() -> stationRepo.findByAddress(namePart));
            if (station.isEmpty()) return Optional.empty();
            if (indexPart.isEmpty()) return Optional.of(station.get().getAddress());
            try {
                int terminalIndex = Integer.parseInt(indexPart);
                return nodeRepo.findTerminalByIndex(station.get().getId(), terminalIndex)
                    .map(t -> dev.netro.util.AddressHelper.terminalAddress(station.get().getAddress(), terminalIndex));
            } catch (NumberFormatException ignored) {
            }
            Optional<dev.netro.model.TransferNode> node = nodeRepo.findByNameAtStation(station.get().getId(), indexPart);
            if (node.isEmpty()) return Optional.empty();
            if (node.get().isTerminal() && node.get().getTerminalIndex() != null) {
                return Optional.of(dev.netro.util.AddressHelper.terminalAddress(station.get().getAddress(), node.get().getTerminalIndex()));
            }
            return Optional.of(station.get().getAddress());
        }

        return stationRepo.findByNameIgnoreCase(s)
            .or(() -> stationRepo.findByAddress(s))
            .map(Station::getAddress);
    }

    /**
     * Normalize a destination string for storage (e.g. in rules). When the value can be resolved to a new-format
     * 6/7-part address, returns that; otherwise returns the original. For transfer nodes (non-terminal) we keep
     * "StationName:NodeName" so the rule list can show the node name instead of only the station.
     */
    public static String normalizeToNewFormatForStorage(StationRepository stationRepo,
                                                        TransferNodeRepository nodeRepo,
                                                        String value) {
        if (value == null || value.isBlank()) return value;
        if (AddressHelper.parseDestination(value).isPresent() || AddressHelper.isNewFormatStationAddress(value))
            return value;
        Optional<String> resolved = resolveToAddress(stationRepo, nodeRepo, value);
        if (resolved.isEmpty()) return value;
        String r = resolved.get();
        if (!AddressHelper.parseDestination(r).isPresent() && !AddressHelper.isNewFormatStationAddress(r)) return value;
        Optional<AddressHelper.ParsedDestination> parsed = AddressHelper.parseDestination(r);
        boolean isTerminal = parsed.isPresent() && parsed.get().terminalIndex() != null;
        if (isTerminal) return r;
        if (value.contains(":") && !value.equals(r)) return value;
        return r;
    }
}

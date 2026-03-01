package dev.netro.util;

import dev.netro.database.StationRepository;
import dev.netro.database.TransferNodeRepository;
import dev.netro.model.Station;

import java.util.Optional;

/**
 * Resolves destination strings to full addresses for cart routing.
 * Supports: numeric address (e.g. 2.4.7.3), station name (e.g. Snowy2 → any terminal),
 * and Name:TerminalIndex (e.g. Snowy2:0 → that terminal's address).
 */
public final class DestinationResolver {

    private DestinationResolver() {}

    /**
     * Resolves a destination string to a full address (station address or station address + "." + terminal index).
     *
     * @param stationRepo station repository
     * @param nodeRepo    transfer node repository (for terminal-by-index)
     * @param input      address (e.g. 2.4.7.3), station name (e.g. Snowy2), or Name:TerminalIndex (e.g. Snowy2:0)
     * @return the full address to set as destination, or empty if not found
     */
    public static Optional<String> resolveToAddress(StationRepository stationRepo,
                                                     TransferNodeRepository nodeRepo,
                                                     String input) {
        if (input == null || input.isBlank()) return Optional.empty();
        String s = input.strip();

        if (s.matches("[0-9.]+")) {
            return stationRepo.findByAddress(s)
                .map(Station::getAddress)
                .or(() -> Optional.of(s));
        }

        int colon = s.indexOf(':');
        if (colon >= 0) {
            String namePart = s.substring(0, colon).strip();
            String indexPart = s.substring(colon + 1).strip();
            Optional<Station> station = stationRepo.findByNameIgnoreCase(namePart)
                .or(() -> stationRepo.findByAddress(namePart));
            if (station.isEmpty()) return Optional.empty();
            if (indexPart.isEmpty()) return Optional.of(station.get().getAddress());
            int terminalIndex;
            try {
                terminalIndex = Integer.parseInt(indexPart);
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
            return nodeRepo.findTerminalByIndex(station.get().getId(), terminalIndex)
                .map(t -> station.get().getAddress() + "." + terminalIndex);
        }

        return stationRepo.findByNameIgnoreCase(s)
            .or(() -> stationRepo.findByAddress(s))
            .map(Station::getAddress);
    }
}

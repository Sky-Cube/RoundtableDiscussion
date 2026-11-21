package com.aipanel.service.debate;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.aipanel.model.Participant;
import com.aipanel.model.Utterance;

public final class BidArbitrator {

    private BidArbitrator() {
    }

    public record Bid(Participant expert, String intent, Integer target, String focus, String claim,
            String searchQuery, String searchPremise, boolean speechReady) {
        public Bid(Participant expert, String intent, Integer target, String focus, String claim,
                String searchQuery, String searchPremise) {
            this(expert, intent, target, focus, claim, searchQuery, searchPremise, false);
        }

        public Bid(Participant expert, String intent, Integer target, String focus, String claim, String searchQuery) {
            this(expert, intent, target, focus, claim, searchQuery, null);
        }

        public String verificationClaim() {
            return searchPremise != null && searchPremise.length() >= 8 && claim != null && claim.contains(searchPremise)
                    ? searchPremise : claim;
        }

        public Bid(Participant expert, String intent, Integer target, String focus, String claim) {
            this(expert, intent, target, focus, claim, null);
        }
    }

    public static Optional<Bid> select(PhasePlan phase, List<Bid> bids, List<Utterance> transcript) {
        List<String> speakers = transcript.stream().filter(line -> "EXPERT".equals(line.kind()))
                .map(Utterance::participantId).toList();
        List<Bid> candidates = bids.stream().filter(bid -> !"PASS".equals(bid.intent())).toList();
        String lastSpeaker = speakers.isEmpty() ? null : speakers.getLast();
        boolean repeated = speakers.size() >= 2 && lastSpeaker.equals(speakers.get(speakers.size() - 2));
        boolean mustYield = repeated && candidates.stream().anyMatch(bid -> !bid.expert().id().equals(lastSpeaker));
        return candidates.stream().filter(bid -> !mustYield || !bid.expert().id().equals(lastSpeaker))
                .min(Comparator.<Bid>comparingInt(bid -> score(phase, bid, speakers)).reversed()
                        .thenComparingInt(bid -> speakers.lastIndexOf(bid.expert().id()))
                        .thenComparing(bid -> bid.expert().id()));
    }

    private static int score(PhasePlan phase, Bid bid, List<String> speakers) {
        int weight = switch (bid.intent()) {
            case "REBUT" -> 3;
            case "ADD", "BUZZ" -> 2;
            default -> 1;
        };
        int focusIndex = phase.focus().indexOf(bid.focus());
        int focus = focusIndex == 0 ? 2 : focusIndex > 0 ? 1 : 0;
        return weight + focus + Math.min(2, speakers.size() - 1 - speakers.lastIndexOf(bid.expert().id()));
    }
}

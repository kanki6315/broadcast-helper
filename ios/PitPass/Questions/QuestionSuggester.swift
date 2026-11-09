import Foundation
import FoundationModels

/// Sources come first so the model picks what a question is about before
/// writing it — the order of @Generable properties is the order of generation.
@Generable
struct SuggestedQuestion: Equatable {
    @Guide(description: "IDs of the lines this question is built on, such as F3, S12 or C1. Use only IDs that appear in the briefing.")
    var sources: [String]
    @Guide(description: "A two- or three-word label for the angle, such as Championship fight or Sebring history")
    var angle: String
    @Guide(description: "The question exactly as the commentator would ask it to the driver: one or two sentences, spoken English, second person")
    var question: String
}

@Generable
struct QuestionSet: Equatable {
    @Guide(description: "Distinct questions, each on a different angle, best first", .count(6))
    var questions: [SuggestedQuestion]
}

/// Which model answered, for the UI and for comparing quality.
enum QuestionModel: String, Sendable {
    case privateCloud = "Private Cloud Compute"
    case onDevice = "On-device"
}

struct QuestionSuggestions: Sendable {
    let questions: [SuggestedQuestion]
    let model: QuestionModel
    /// Why Private Cloud Compute wasn't used, when it wasn't.
    let fallbackReason: String?
    /// The exact briefing sent, so a weak answer can be traced to its input.
    let prompt: String
}

enum QuestionSuggester {
    static let instructions = """
        You help a motorsport pit-lane reporter prepare questions for a short interview with one driver \
        during a race weekend broadcast.

        Rules:
        - Base every question on the numbered briefing lines and cite their IDs. Never add facts, numbers, \
        names or results that are not in the briefing.
        - Prefer lines marked * (they are about this driver, car or team). Use general storylines only \
        when they clearly involve this driver's class, car or team.
        - Ask open questions that let the driver tell a story: how, what, why, walk us through. \
        Avoid yes/no questions and avoid "how do you feel".
        - Start with the biggest facts about this driver: a defending win, a championship lead or chase, \
        their record at this track, a streak. At least four questions must cite an F line.
        - State the fact inside the question so viewers get the context, then ask about it.
        - Keep each question short enough to say in one breath.
        - If the briefing includes earlier conversations, don't repeat those topics; follow up on them.
        - If the commentator named a topic, make at least two questions about it.

        Shape of a good question (the facts in brackets come from the briefing, never from here):
        "[Fact about the driver's result or record]. [Open question about how or why]?"
        """

    /// Characters, not tokens: about 3 characters a token for this mixed
    /// numeric text, with room left for the instructions and the answer.
    static func budget(contextTokens: Int) -> Int { max(2_000, (contextTokens - 1_800) * 3) }

    /// Private Cloud Compute first (32K context: the whole storylines pack),
    /// then the on-device model with the pack trimmed to fit its window.
    static func suggest(_ briefing: DriverBriefing, allowPrivateCloud: Bool = true) async throws -> QuestionSuggestions {
        var reason: String? = allowPrivateCloud ? nil : "Turned off"
        if allowPrivateCloud {
            if #available(iOS 27.0, macOS 27.0, *) {
                let pcc = PrivateCloudComputeLanguageModel()
                switch pcc.availability {
                case .available where !pcc.quotaUsage.isLimitReached:
                    let context = (try? await pcc.contextSize) ?? 32_768
                    let prompt = briefing.prompt(budget: budget(contextTokens: context))
                    do {
                        let session = LanguageModelSession(model: pcc, instructions: instructions)
                        let set = try await session.respond(to: prompt, generating: QuestionSet.self).content
                        return QuestionSuggestions(questions: validated(set, briefing), model: .privateCloud,
                                                   fallbackReason: nil, prompt: prompt)
                    } catch {
                        reason = describe(error)
                    }
                case .available:
                    reason = "Daily Private Cloud Compute limit reached"
                case .unavailable(let why):
                    reason = "Private Cloud Compute unavailable (\(why))"
                }
            } else {
                reason = "Private Cloud Compute needs iPadOS 27"
            }
        }
        let model = SystemLanguageModel.default
        guard case .available = model.availability else { throw SuggestError.unavailable(model.availability) }
        let prompt = await fitted(briefing, to: model)
        let session = LanguageModelSession(model: model, instructions: instructions)
        let set = try await session.respond(to: prompt, generating: QuestionSet.self).content
        return QuestionSuggestions(questions: validated(set, briefing), model: .onDevice, fallbackReason: reason, prompt: prompt)
    }

    /// Room kept for six questions with their sources.
    static let answerTokens = 900

    /// The largest briefing that fits the on-device window, measured with the
    /// model's own tokenizer where the OS has it; storylines give way first.
    static func fitted(_ briefing: DriverBriefing, to model: SystemLanguageModel) async -> String {
        // A small model drifts into general storylines, so it only gets the
        // ones about this driver, car or team.
        var chars = budget(contextTokens: model.contextSize)
        var prompt = briefing.prompt(budget: chars, generalStorylines: false)
        guard #available(iOS 26.4, macOS 26.4, *) else { return briefing.prompt(budget: chars * 3 / 4, generalStorylines: false) }
        guard let fixed = try? await model.tokenCount(for: Instructions(instructions))
                + model.tokenCount(for: QuestionSet.generationSchema) else { return prompt }
        let limit = model.contextSize - fixed - answerTokens
        for _ in 0..<6 {
            guard let used = try? await model.tokenCount(for: prompt), used > limit else { return prompt }
            chars = chars * limit / used * 95 / 100
            prompt = briefing.prompt(budget: chars, generalStorylines: false)
        }
        return prompt
    }

    /// Drop citations the model invented; a question citing nothing real is
    /// kept but shows no source, which is the reader's cue to check it.
    static func validated(_ set: QuestionSet, _ briefing: DriverBriefing) -> [SuggestedQuestion] {
        let known = briefing.lines
        return set.questions.map { q in
            var q = q
            q.sources = q.sources.map { $0.trimmingCharacters(in: CharacterSet(charactersIn: " *")) }.filter { known[$0] != nil }
            return q
        }
    }

    static func describe(_ error: Error) -> String {
        if #available(iOS 27.0, macOS 27.0, *), let pcc = error as? PrivateCloudComputeLanguageModel.Error {
            switch pcc {
            case .networkFailure: return "No connection for Private Cloud Compute"
            case .quotaLimitReached: return "Daily Private Cloud Compute limit reached"
            case .serviceUnavailable: return "Private Cloud Compute is busy"
            @unknown default: return pcc.localizedDescription
            }
        }
        // Without the managed entitlement the request fails inside
        // ModelManager (code 1046) even though availability says .available.
        let text = String(describing: error)
        if text.contains("ModelManagerError Code=1046") { return "Private Cloud Compute not enabled for this app yet" }
        return error.localizedDescription
    }

    enum SuggestError: LocalizedError {
        case unavailable(SystemLanguageModel.Availability)
        var errorDescription: String? {
            switch self {
            case .unavailable(.unavailable(.deviceNotEligible)): "This iPad doesn't support Apple Intelligence."
            case .unavailable(.unavailable(.appleIntelligenceNotEnabled)): "Turn on Apple Intelligence in Settings to suggest questions."
            case .unavailable(.unavailable(.modelNotReady)): "Apple Intelligence is still downloading. Try again shortly."
            case .unavailable: "Apple Intelligence isn't available right now."
            }
        }
    }
}

import SwiftUI

/// RecapModal: the season recap over the event sheet — the same grids, with
/// this event's round column marked in amber. Selection is local to the sheet.
struct RecapSheet: View {
    @Environment(AppSession.self) private var session
    @Environment(\.dismiss) private var dismiss
    @State private var model: SeasonModel

    init(seasonId: Int, currentEventId: Int) {
        let m = SeasonModel(seasonId: seasonId)
        m.currentEventId = currentEventId
        _model = State(initialValue: m)
    }

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: PP.Space.s3) {
                    if let hub = model.hub.value {
                        Text("\(hub.seriesName) \(String(hub.year)) · season recap").ppTitle()
                        if model.classes.count > 1 { ClassChipRow(classes: model.classes, selection: $model.classFilter) }
                        ChampionshipGridView(mode: .recap)
                    } else if let error = model.hub.error {
                        ErrorPanel(message: error)
                    } else {
                        SkeletonLines()
                    }
                }
                .padding(PP.Space.s5)
            }
            .background(PP.bg.ignoresSafeArea())
            .navigationTitle("Recap")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .presentationBackground(PP.bg)
        .presentationSizing(.page)
        .tint(PP.accentInk)
        .environment(model)
        .task { await model.load(session) }
    }
}

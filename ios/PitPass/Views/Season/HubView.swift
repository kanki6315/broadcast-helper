import SwiftUI

/// Overview puts championship position and season form directly below the class filter.
struct HubView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            PageTitle(text: "Season recap")
            ChampionshipGridView(mode: .recap)
        }
    }
}

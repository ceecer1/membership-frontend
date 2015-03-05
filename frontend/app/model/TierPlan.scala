package model

import com.gu.membership.salesforce.Tier
import model.PaidTiers.isPaid


object PaidTiers {
  def isPaid(tier: Tier) = tier >= Tier.Supporter
}

trait ProductRatePlan {
  def salesforceTier: String
}

trait TierPlan extends ProductRatePlan {
  val tier: Tier
  def salesforceTier = tier.name
}

object FriendTierPlan extends TierPlan {
  val tier = Tier.Friend

  override val hashCode = 0 // This is here to give TouchpointBackendConfig a consistent hash over multiple JVM runs
}

case class PaidTierPlan(tier: Tier, annual: Boolean) extends TierPlan {
  assert(isPaid(tier))
}

object StaffPlan extends ProductRatePlan {
  def salesforceTier = "Staff"
}

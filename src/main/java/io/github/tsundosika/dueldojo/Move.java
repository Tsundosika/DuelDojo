package io.github.tsundosika.dueldojo;

/**
 * Moves worth naming, recognised from what just happened in a fight. They show up as live notes and in a line after
 * the match, so you learn what the moves are called and notice when you pull one off.
 */
enum Move {
	PICK("Pick hit", "An early hit on someone flying out of your reach: it stops their knockback."),
	UPPERCUT("Uppercut", "A sprint hit while jumping forward: it covers a long distance fast."),
	FALLING_SPRINT("Falling sprint hit", "A sprint hit on the way down: it knocks them away when they expect a crit."),
	DEFLECTION("Crit deflection", "A sprint hit right after they crit you: it knocks them away."),
	HIT_SELECT("Hit select", "Letting them hit you, then sprint-hitting straight back: you take less knockback."),
	PUNISH_CRIT("Punish crit", "They knock you into the air, and you crit them on the way back down.");

	final String title;
	final String detail;

	Move(String title, String detail) {
		this.title = title;
		this.detail = detail;
	}
}

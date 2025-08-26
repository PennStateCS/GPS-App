package com.example.surveyingapp.data

/**
 * Type alias for backward compatibility.
 *
 * In earlier versions of the app, coordinate points were called "Point".
 * Now they're called "Coordinate" for clarity, but some old code still references "Point".
 *
 * A typealias creates an alternative name for an existing type without creating a new class.
 * This means Point and Coordinate are exactly the same thing - just different names.
 *
 * This is a common pattern when refactoring code to maintain compatibility.
 */

typealias Point = Coordinate

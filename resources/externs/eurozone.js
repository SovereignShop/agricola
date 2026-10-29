/** @externs */
// Boundary between Closure-compiled CLJS and the standalone Discord/peer bundles.
Window.prototype.EUROZONE_READY;
Window.prototype.EUROZONE_ACTIVITY;
Window.prototype.EUROZONE_ACTIVITY_LOBBY;
Window.prototype.EUROZONE_DISCORD_USER;
Window.prototype.EUROZONE_DISCORD_AUTH;
Window.prototype.EUROZONE_RTC_CONFIG;
Window.prototype.EUROZONE_ACTIVITY_CHANGED = function(table) {};
Window.prototype.EUROZONE_SET_ACTIVITY_URL = function(table) {};
Window.prototype.EUROZONE_ACTIVITY_TABLES = function(action, room, requestId) {};
Window.prototype.EUROZONE_ENTER_ACTIVITY_TABLE = function(table) {};
Window.prototype.EUROZONE_ACTIVITY_HOME = function() {};
Window.prototype.EUROZONE_INVITE = function() {};
/** @constructor */ var EurozoneTransport = function() {};
EurozoneTransport.prototype.send = function(id, text) {};
EurozoneTransport.prototype.initialized = function() {};
EurozoneTransport.prototype.close = function() {};
var EurozonePeer = {};
/** @return {EurozoneTransport} */ EurozonePeer.start = function(options) {};
EurozonePeer.read = function(room) {};
EurozonePeer.save = function(room, text) {};
/** @constructor */ var EurozoneTableResult = function() {};
EurozoneTableResult.prototype.tables;
EurozoneTableResult.prototype.room;
EurozoneTableResult.prototype.initialized;
/** @constructor */ var EurozoneRandom = function() {};
EurozoneRandom.prototype.nextInt = function(n) {};
EurozoneRandom.prototype.nextDouble = function() {};

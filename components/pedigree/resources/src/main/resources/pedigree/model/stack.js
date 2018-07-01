define([], function(){
    var Stack = function() {
        this.data = [];
    };

    Stack.prototype = {

        setTo: function(list) {
            this.data = list.slice();
        },

        push: function(v) {
            this.data.push(v);
        },

        pop: function(v) {
            return this.data.pop();
        },

        size: function() {
            return this.data.length;
        }
    };

    return Stack;
});

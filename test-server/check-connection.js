/** Quick MongoDB connectivity check: `npm run check`. */
require('dotenv').config();
const mongoose = require('mongoose');

const uri = process.env.MONGODB_URI || 'mongodb://127.0.0.1:27017/geolocation_test';
console.log('Checking MongoDB connection…');

mongoose
  .connect(uri, { serverSelectionTimeoutMS: 15000 })
  .then(async () => {
    console.log(`✅ Connected — database "${mongoose.connection.name}"`);
    await mongoose.disconnect();
    process.exit(0);
  })
  .catch((error) => {
    console.error(`❌ Connection failed: ${error.message}`);
    console.error(
      'If this mentions IP/whitelist or times out: in MongoDB Atlas go to ' +
        'Network Access → Add IP Address → "Allow access from anywhere" (for testing).'
    );
    process.exit(1);
  });
